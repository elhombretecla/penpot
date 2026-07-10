use skia_safe::{self as skia, textlayout, Font, FontMgr};
use std::cell::Cell;
use std::collections::HashSet;

use crate::error::{Error, Result};
use crate::shapes::{FontFamily, FontStyle};
use crate::uuid::Uuid;

pub static DEFAULT_EMOJI_FONT: &str = "noto-color-emoji";

const DEFAULT_FONT_BYTES: &[u8] = include_bytes!("../fonts/sourcesanspro-regular.ttf");
const UI_FONT_BYTES: &[u8] = include_bytes!("../fonts/WorkSans-Numeric.ttf");

pub fn default_font() -> String {
    let family = FontFamily::new(default_font_uuid(), 400, FontStyle::Normal);
    format!("{}", family)
}

fn default_font_uuid() -> Uuid {
    Uuid::nil()
}

pub struct FontStore {
    font_mgr: FontMgr,
    font_provider: textlayout::TypefaceFontProvider,
    font_collection: textlayout::FontCollection,
    debug_font: Font,
    ui_font: Font,
    fallback_fonts: HashSet<String>,
    /// Registered font names (family alias, or the shared emoji alias) for
    /// O(1) `has_family` checks instead of scanning the provider names.
    registered: HashSet<String>,
    /// Set when a font is registered; the shaping caches of the (shared)
    /// FontCollection are flushed lazily on the next `font_collection()`
    /// access instead of once per `add` — bulk font loads would otherwise
    /// discard the whole cache N times in a row.
    caches_dirty: Cell<bool>,
    /// Bumped on every successful font registration. Cached text layouts
    /// key on this so they rebuild once the fonts they may depend on load.
    generation: u64,
}

impl FontStore {
    pub fn try_new() -> Result<Self> {
        let font_mgr = FontMgr::new();
        let font_provider = load_default_provider(&font_mgr);
        let mut font_collection = skia::textlayout::FontCollection::new();
        font_collection.set_default_font_manager(FontMgr::from(font_provider.clone()), None);

        let debug_typeface = font_provider
            .match_family_style(default_font().as_str(), skia::FontStyle::default())
            .ok_or(Error::CriticalError(
                "Failed to match default font".to_string(),
            ))?;

        let debug_font = skia::Font::new(debug_typeface, 12.0);

        let ui_typeface = font_mgr
            .new_from_data(UI_FONT_BYTES, None)
            .ok_or(Error::CriticalError("Failed to load UI font".to_string()))?;
        let ui_font = skia::Font::new(ui_typeface, 12.0);

        let mut registered = HashSet::new();
        registered.insert(default_font());

        Ok(Self {
            font_mgr,
            font_provider,
            font_collection,
            debug_font,
            ui_font,
            fallback_fonts: HashSet::new(),
            registered,
            caches_dirty: Cell::new(false),
            generation: 0,
        })
    }

    pub fn set_scale_debug_font(&mut self, dpr: f32) {
        let debug_font = skia::Font::new(self.debug_font.typeface(), 12.0 * dpr);
        self.debug_font = debug_font;
    }

    pub fn font_provider(&self) -> &textlayout::TypefaceFontProvider {
        &self.font_provider
    }

    pub fn font_collection(&self) -> &textlayout::FontCollection {
        if self.caches_dirty.get() {
            self.caches_dirty.set(false);
            // FontCollection is a refcounted handle to a shared native
            // object; clearing through a clone clears the same caches.
            let mut collection = self.font_collection.clone();
            collection.clear_caches();
        }
        &self.font_collection
    }

    pub fn debug_font(&self) -> &Font {
        &self.debug_font
    }

    pub fn ui_font(&self) -> &Font {
        &self.ui_font
    }

    pub fn add(
        &mut self,
        family: FontFamily,
        font_data: &[u8],
        is_emoji: bool,
        is_fallback: bool,
    ) -> Result<()> {
        if self.has_family(&family, is_emoji) {
            return Ok(());
        }

        let typeface = self
            .font_mgr
            .new_from_data(font_data, None)
            .ok_or(Error::CriticalError(
                "Failed to create typeface".to_string(),
            ))?;

        let alias = format!("{}", family);
        let font_name = if is_emoji {
            DEFAULT_EMOJI_FONT
        } else {
            alias.as_str()
        };

        self.font_provider.register_typeface(typeface, font_name);
        self.registered.insert(font_name.to_string());
        self.caches_dirty.set(true);
        self.generation = self.generation.wrapping_add(1);

        if is_fallback {
            self.fallback_fonts.insert(alias);
        }

        Ok(())
    }

    pub fn has_family(&self, family: &FontFamily, is_emoji: bool) -> bool {
        if is_emoji {
            self.registered.contains(DEFAULT_EMOJI_FONT)
        } else {
            self.registered.contains(&family.alias())
        }
    }

    pub fn get_fallback(&self) -> &HashSet<String> {
        &self.fallback_fonts
    }

    pub fn generation(&self) -> u64 {
        self.generation
    }

    pub fn get_emoji_font(&self, _size: f32) -> Option<Font> {
        None
    }
}

fn load_default_provider(font_mgr: &FontMgr) -> skia::textlayout::TypefaceFontProvider {
    let mut font_provider = skia::textlayout::TypefaceFontProvider::new();

    let family = FontFamily::new(default_font_uuid(), 400, FontStyle::Normal);
    let font = font_mgr
        .new_from_data(DEFAULT_FONT_BYTES, None)
        .expect("Failed to load font");
    font_provider.register_typeface(font, family.alias().as_str());

    font_provider
}
