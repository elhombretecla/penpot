use crate::math::Rect as MathRect;
use crate::shapes::ImageFill;
use crate::uuid::Uuid;

use crate::error::Result;
use crate::get_gpu_state;
use skia_safe::gpu::{surfaces, Budgeted, DirectContext};
use skia_safe::{self as skia, Codec, ISize};
use std::collections::HashMap;

pub type Image = skia::Image;

pub fn get_dest_rect(container: &MathRect, delta: f32) -> MathRect {
    MathRect::from_ltrb(
        container.left - delta,
        container.top - delta,
        container.right + delta,
        container.bottom + delta,
    )
}

pub fn get_source_rect(size: ISize, container: &MathRect, image_fill: &ImageFill) -> MathRect {
    let image_width = size.width as f32;
    let image_height = size.height as f32;

    // Container size
    let container_width = container.width();
    let container_height = container.height();

    let mut source_width = image_width;
    let mut source_height = image_height;
    let mut source_x = 0.;
    let mut source_y = 0.;

    let source_scale_y = image_height / container_height;
    let source_scale_x = image_width / container_width;

    if image_fill.keep_aspect_ratio() {
        // Calculate scale to ensure the image covers the container
        let image_aspect_ratio = image_width / image_height;
        let container_aspect_ratio = container_width / container_height;

        if image_aspect_ratio > container_aspect_ratio {
            // Image is taller, scale based on width to cover container
            source_width = container_width * source_scale_y;
            source_x = (image_width - source_width) / 2.0;
        } else {
            // Image is wider, scale based on height to cover container
            source_height = container_height * source_scale_x;
            source_y = (image_height - source_height) / 2.0;
        };
    }

    MathRect::from_xywh(source_x, source_y, source_width, source_height)
}

enum StoredImage {
    Raw(Vec<u8>),
    Gpu(Image),
}

impl StoredImage {
    fn byte_size(&self) -> usize {
        match self {
            StoredImage::Raw(data) => data.len(),
            // RGBA8 texture estimate.
            StoredImage::Gpu(img) => (img.width() as usize) * (img.height() as usize) * 4,
        }
    }
}

struct StoredEntry {
    image: StoredImage,
    bytes: usize,
    last_used_frame: u64,
}

/// Byte budget for decoded/uploaded images. Beyond it, least-recently-used
/// entries are evicted (issue #7334: unbounded growth reached ~7 GB on large
/// libraries). Evicted full-resolution images are reported so the frontend
/// can re-fetch them on demand.
const IMAGE_CACHE_BUDGET_BYTES: usize = 512 * 1024 * 1024;
/// Entries used within this many frames are never evicted.
const IMAGE_EVICTION_MIN_AGE_FRAMES: u64 = 8;

pub struct ImageStore {
    images: HashMap<(Uuid, bool), StoredEntry>,
    context: Box<DirectContext>,
    /// Bumped on every stored image. Cached text layouts with image fills
    /// key on this so they rebuild once their image arrives.
    generation: u64,
    total_bytes: usize,
    current_frame: u64,
}

/// Creates a Skia image from an existing WebGL texture.
/// This avoids re-decoding the image, as the browser has already decoded
/// and uploaded it to the GPU.
fn create_image_from_gl_texture(
    context: &mut Box<DirectContext>,
    texture_id: u32,
    width: i32,
    height: i32,
) -> Result<Image> {
    use skia_safe::gpu;
    use skia_safe::gpu::gl::TextureInfo;

    // Create a TextureInfo describing the existing GL texture
    let texture_info = TextureInfo {
        target: gl::TEXTURE_2D,
        id: texture_id,
        format: gl::RGBA8,
        protected: gpu::Protected::No,
    };

    // Create a backend texture from the GL texture using the new API
    let label = format!("shared_texture_{}", texture_id);
    let backend_texture = unsafe {
        gpu::backend_textures::make_gl((width, height), gpu::Mipmapped::No, texture_info, label)
    };

    // Create a Skia image from the backend texture
    // Use TopLeft origin because HTML images have their origin at top-left,
    // while WebGL textures traditionally use bottom-left
    let image = Image::from_texture(
        context.as_mut(),
        &backend_texture,
        gpu::SurfaceOrigin::TopLeft,
        skia::ColorType::RGBA8888,
        skia::AlphaType::Premul,
        None,
    )
    .ok_or(crate::error::Error::CriticalError(
        "Failed to create Skia image from GL texture".to_string(),
    ))?;

    Ok(image)
}

// Decode and upload to GPU
fn decode_image(context: &mut Box<DirectContext>, raw_data: &[u8]) -> Option<Image> {
    let data = unsafe { skia::Data::new_bytes(raw_data) };
    let codec = Codec::from_data(&data)?;
    let image = Image::from_encoded(&data)?;

    let mut dimensions = codec.dimensions();
    if codec.origin().swaps_width_height() {
        dimensions.width = codec.dimensions().height;
        dimensions.height = codec.dimensions().width;
    }

    let image_info = skia::ImageInfo::new_n32_premul(dimensions, None);

    let mut surface = surfaces::render_target(
        context,
        Budgeted::Yes,
        &image_info,
        None,
        None,
        None,
        true,
        false,
    )?;

    let dest_rect: MathRect =
        MathRect::from_xywh(0.0, 0.0, dimensions.width as f32, dimensions.height as f32);

    surface
        .canvas()
        .draw_image_rect(&image, None, dest_rect, &skia::Paint::default());

    Some(surface.image_snapshot())
}

impl ImageStore {
    pub fn new() -> Self {
        let gpu_state = get_gpu_state();
        let context = &gpu_state.context;
        Self {
            images: HashMap::with_capacity(2048),
            context: Box::new(context.clone()),
            generation: 0,
            total_bytes: 0,
            current_frame: 0,
        }
    }

    pub fn generation(&self) -> u64 {
        self.generation
    }

    /// Called once per rAF so LRU stamps distinguish frames.
    pub fn begin_frame(&mut self) {
        self.current_frame = self.current_frame.wrapping_add(1);
    }

    fn insert_entry(&mut self, key: (Uuid, bool), image: StoredImage) {
        let bytes = image.byte_size();
        let entry = StoredEntry {
            image,
            bytes,
            last_used_frame: self.current_frame,
        };
        if let Some(prev) = self.images.insert(key, entry) {
            self.total_bytes = self.total_bytes.saturating_sub(prev.bytes);
        }
        self.total_bytes += bytes;
        self.generation = self.generation.wrapping_add(1);
        self.evict_over_budget();
    }

    /// Evicts entries until the byte budget is respected. Preference order:
    /// 1. Thumbnails whose full-resolution image is present (never needed
    ///    again — `get` always prefers the full image).
    /// 2. Least-recently-used entries not touched in the last
    ///    `IMAGE_EVICTION_MIN_AGE_FRAMES` frames. Evicted full images are
    ///    reported to the frontend for on-demand re-fetch.
    fn evict_over_budget(&mut self) {
        if self.total_bytes <= IMAGE_CACHE_BUDGET_BYTES {
            return;
        }

        // Pass 1: drop superseded thumbnails.
        let superseded: Vec<(Uuid, bool)> = self
            .images
            .keys()
            .filter(|(id, is_thumbnail)| *is_thumbnail && self.images.contains_key(&(*id, false)))
            .copied()
            .collect();
        for key in superseded {
            if let Some(prev) = self.images.remove(&key) {
                self.total_bytes = self.total_bytes.saturating_sub(prev.bytes);
            }
        }
        if self.total_bytes <= IMAGE_CACHE_BUDGET_BYTES {
            return;
        }

        // Pass 2: LRU among entries old enough to be safely dropped.
        let mut candidates: Vec<((Uuid, bool), u64, usize)> = self
            .images
            .iter()
            .filter(|(_, e)| {
                self.current_frame.saturating_sub(e.last_used_frame)
                    >= IMAGE_EVICTION_MIN_AGE_FRAMES
            })
            .map(|(k, e)| (*k, e.last_used_frame, e.bytes))
            .collect();
        candidates.sort_unstable_by_key(|(_, last_used, _)| *last_used);

        let mut evicted_full: Vec<Uuid> = Vec::new();
        for (key, _, bytes) in candidates {
            if self.total_bytes <= IMAGE_CACHE_BUDGET_BYTES {
                break;
            }
            self.images.remove(&key);
            self.total_bytes = self.total_bytes.saturating_sub(bytes);
            if !key.1 {
                evicted_full.push(key.0);
            }
        }

        if !evicted_full.is_empty() {
            crate::wapi::notify_images_evicted(&evicted_full);
        }
    }

    pub fn add(
        &mut self,
        id: Uuid,
        is_thumbnail: bool,
        image_data: &[u8],
    ) -> crate::error::Result<()> {
        let key = (id, is_thumbnail);

        if self.images.contains_key(&key) {
            return Ok(());
        }

        let raw_data = image_data.to_vec();

        if let Some(gpu_image) = decode_image(&mut self.context, &raw_data) {
            self.insert_entry(key, StoredImage::Gpu(gpu_image));
        } else {
            self.insert_entry(key, StoredImage::Raw(raw_data));
        }
        Ok(())
    }

    /// Creates a Skia image from an existing WebGL texture, avoiding re-decoding.
    /// This is much more efficient as it reuses the texture that was already
    /// decoded and uploaded to GPU by the browser.
    pub fn add_image_from_gl_texture(
        &mut self,
        id: Uuid,
        is_thumbnail: bool,
        texture_id: u32,
        width: i32,
        height: i32,
    ) -> Result<()> {
        let key = (id, is_thumbnail);

        if self.images.contains_key(&key) {
            return Ok(());
        }

        // Create a Skia image from the existing GL texture
        let image = create_image_from_gl_texture(&mut self.context, texture_id, width, height)?;
        self.insert_entry(key, StoredImage::Gpu(image));

        Ok(())
    }

    pub fn contains(&self, id: &Uuid, is_thumbnail: bool) -> bool {
        self.images.contains_key(&(*id, is_thumbnail))
    }

    pub fn get(&mut self, id: &Uuid) -> Option<&Image> {
        // Try to get full image first, fallback to thumbnail
        let has_full = self.images.contains_key(&(*id, false));
        if has_full {
            self.get_internal(id, false)
        } else {
            self.get_internal(id, true)
        }
    }

    pub fn get_cpu_image(&mut self, id: &Uuid) -> Option<Image> {
        let gpu_image = self.get(id)?.clone();
        gpu_image.make_non_texture_image(self.context.as_mut())
    }

    fn get_internal(&mut self, id: &Uuid, is_thumbnail: bool) -> Option<&Image> {
        let key = (*id, is_thumbnail);
        let current_frame = self.current_frame;
        // Use entry API to mutate the HashMap in-place if needed
        if let Some(entry) = self.images.get_mut(&key) {
            entry.last_used_frame = current_frame;
            match &mut entry.image {
                StoredImage::Gpu(_) => {}
                StoredImage::Raw(raw_data) => {
                    let gpu_image = decode_image(&mut self.context, raw_data)?;
                    let new_bytes =
                        (gpu_image.width() as usize) * (gpu_image.height() as usize) * 4;
                    let old_bytes = entry.bytes;
                    entry.image = StoredImage::Gpu(gpu_image);
                    entry.bytes = new_bytes;
                    self.total_bytes = self
                        .total_bytes
                        .saturating_sub(old_bytes)
                        .saturating_add(new_bytes);
                }
            }
            match &entry.image {
                StoredImage::Gpu(ref img) => Some(img),
                _ => None,
            }
        } else {
            None
        }
    }
}
