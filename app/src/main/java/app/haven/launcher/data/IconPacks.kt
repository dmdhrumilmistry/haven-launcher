package app.haven.launcher.data

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Third-party icon packs in the common format most launchers support (ADW / Nova / Apex): the pack
 * declares an `appfilter.xml` mapping app components to its drawables.
 */
class IconPacks(private val context: Context) {
    data class Pack(val packageName: String, val label: String)

    private class Mapping(val resources: Resources, val drawables: Map<String, String>)

    private val mappings = ConcurrentHashMap<String, Mapping>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    /** Installed icon packs, sorted by name. */
    fun installed(): List<Pack> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, Pack>()
        for (intent in DISCOVERY) {
            val matches = try {
                pm.queryIntentActivities(intent, PackageManager.GET_META_DATA)
            } catch (e: Exception) {
                emptyList()
            }
            for (info in matches) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (pkg !in found) found[pkg] = Pack(pkg, info.loadLabel(pm).toString())
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    /** The pack's icon for this app, or null to fall back to the app's own icon. Blocking. */
    fun drawableFor(packPackage: String, component: ComponentName, densityDpi: Int): Drawable? {
        val mapping = mappingFor(packPackage) ?: return null
        val name = mapping.drawables[component.flattenToString()]
            ?: mapping.drawables[component.packageName + "/" + component.className]
            ?: return null
        return try {
            @SuppressLint("DiscouragedApi")
            val id = mapping.resources.getIdentifier(name, "drawable", packPackage)
            if (id == 0) null else mapping.resources.getDrawableForDensity(id, densityDpi, null)
        } catch (e: Exception) {
            null
        }
    }

    /** Forget a pack's parsed data (it was updated or uninstalled). */
    fun invalidate(packageName: String) {
        mappings.remove(packageName)
        missing.remove(packageName)
    }

    private fun mappingFor(pkg: String): Mapping? {
        mappings[pkg]?.let { return it }
        if (pkg in missing) return null
        val mapping = try {
            load(pkg)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read icon pack $pkg", e)
            null
        }
        if (mapping == null) missing.add(pkg) else mappings[pkg] = mapping
        return mapping
    }

    @SuppressLint("DiscouragedApi")
    private fun load(pkg: String): Mapping? {
        val res = context.packageManager.getResourcesForApplication(pkg)
        val map = HashMap<String, String>()
        val xmlId = res.getIdentifier("appfilter", "xml", pkg)
        if (xmlId != 0) {
            res.getXml(xmlId).use { parse(it, map) }
        } else {
            // Some packs ship it as an asset instead of a compiled resource.
            res.assets.open("appfilter.xml").use { stream ->
                val parser = XmlPullParserFactory.newInstance().newPullParser()
                parser.setInput(stream, null)
                parse(parser, map)
            }
        }
        return if (map.isEmpty()) null else Mapping(res, map)
    }

    private fun parse(parser: XmlPullParser, out: MutableMap<String, String>) {
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "item") {
                val component = parser.getAttributeValue(null, "component")
                val drawable = parser.getAttributeValue(null, "drawable")
                // Format: ComponentInfo{com.example/com.example.MainActivity}
                val inner = component?.substringAfter('{', "")?.substringBefore('}', "")
                if (!inner.isNullOrBlank() && !drawable.isNullOrBlank()) {
                    val cn = ComponentName.unflattenFromString(inner)
                    if (cn != null) out.putIfAbsent(cn.flattenToString(), drawable)
                    out.putIfAbsent(inner, drawable)
                }
            }
            event = parser.next()
        }
    }

    private fun XmlPullParser.use(block: (XmlPullParser) -> Unit) {
        try {
            block(this)
        } finally {
            (this as? android.content.res.XmlResourceParser)?.close()
        }
    }

    companion object {
        private const val TAG = "HavenIconPacks"

        private val DISCOVERY = listOf(
            Intent("org.adw.launcher.THEMES"),
            Intent("com.novalauncher.THEME"),
            Intent(Intent.ACTION_MAIN).addCategory("com.anddoes.launcher.THEME"),
            Intent(Intent.ACTION_MAIN).addCategory("com.teslacoilsw.launcher.THEME"),
        )
    }
}
