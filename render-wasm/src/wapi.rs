#[macro_export]
macro_rules! notify_tiles_render_complete {
    () => {{
        #[cfg(target_arch = "wasm32")]
        unsafe extern "C" {
            pub fn wapi_notifyTilesRenderComplete();
        }

        #[cfg(target_arch = "wasm32")]
        unsafe {
            wapi_notifyTilesRenderComplete()
        };
    }};
}

pub use notify_tiles_render_complete;

/// Notifies the frontend that full-resolution images were evicted from the
/// image cache so it can re-fetch them on demand. Sends the UUIDs as a
/// contiguous LE u32-quartet byte buffer.
#[allow(unused_variables)]
pub fn notify_images_evicted(ids: &[crate::uuid::Uuid]) {
    #[cfg(target_arch = "wasm32")]
    {
        unsafe extern "C" {
            fn wapi_notifyImagesEvicted(ptr: *const u8, len: usize);
        }

        let mut bytes: Vec<u8> = Vec::with_capacity(ids.len() * 16);
        for id in ids {
            let (a, b, c, d) = crate::utils::uuid_to_u32_quartet(id);
            bytes.extend_from_slice(&a.to_le_bytes());
            bytes.extend_from_slice(&b.to_le_bytes());
            bytes.extend_from_slice(&c.to_le_bytes());
            bytes.extend_from_slice(&d.to_le_bytes());
        }
        unsafe { wapi_notifyImagesEvicted(bytes.as_ptr(), bytes.len()) };
    }
}
