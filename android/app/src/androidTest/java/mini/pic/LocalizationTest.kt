package mini.pic

import android.content.Context
import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class LocalizationTest {
    private fun contextFor(language: String): Context {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration().apply { setLocale(Locale.forLanguageTag(language)) }
        return context.createConfigurationContext(configuration)
    }

    @Test fun simplifiedChineseResourcesCoverCoreScreensAndActions() {
        val context = contextFor("zh-CN")
        assertEquals("图库", context.getString(R.string.app_name))
        assertEquals("相册，退出应用", context.getString(R.string.albums_exit_description))
        assertEquals("设置", context.getString(R.string.settings))
        assertEquals("正在加载漫画…", context.getString(R.string.loading_manga))
        assertEquals("复制", context.getString(R.string.action_copy))
        assertEquals("已移动到 Photos (100%)", context.getString(R.string.moved_to_album, "Photos (100%)"))
    }

    @Test fun englishResourcesAndFallbackCoverCoreScreensAndActions() {
        for (language in listOf("en-GB", "fr-FR")) {
            val context = contextFor(language)
            assertEquals("MiniPic", context.getString(R.string.app_name))
            assertEquals("Albums, exit app", context.getString(R.string.albums_exit_description))
            assertEquals("Settings", context.getString(R.string.settings))
            assertEquals("Loading manga…", context.getString(R.string.loading_manga))
            assertEquals("Copy", context.getString(R.string.action_copy))
            assertEquals("Moved to Photos (100%)", context.getString(R.string.moved_to_album, "Photos (100%)"))
        }
    }
}
