package yokai.presentation.komga

import android.os.Bundle
import eu.kanade.tachiyomi.ui.source.browse.BrowseSourceController
import yokai.source.komga.KomgaSource

class KomgaBrowseController(
    bundle: Bundle = Bundle().apply {
        putLong(BrowseSourceController.SOURCE_ID_KEY, KomgaSource.ID)
    },
) : BrowseSourceController(bundle)
