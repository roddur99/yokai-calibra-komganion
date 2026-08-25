package yokai.presentation.gallery

import android.os.Bundle
import eu.kanade.tachiyomi.ui.source.browse.BrowseSourceController
import yokai.source.gallery.GalleryKomganionSource

class GalleryBrowseController(
    bundle: Bundle = Bundle().apply {
        putLong(BrowseSourceController.SOURCE_ID_KEY, GalleryKomganionSource.ID)
    },
) : BrowseSourceController(bundle)
