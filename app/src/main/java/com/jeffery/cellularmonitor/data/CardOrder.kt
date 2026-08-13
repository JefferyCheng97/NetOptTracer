package com.jeffery.cellularmonitor.data

import android.content.Context
import androidx.annotation.StringRes
import com.jeffery.cellularmonitor.R

/**
 * 卡片种类。[key] 用于持久化，改了会让用户已保存的顺序失效，别动。
 *
 * [titleRes] 交给界面显示，放这里是因为排序设置页和卡片本身都要用同一个名字。
 */
enum class CardKind(val key: String, @param:StringRes val titleRes: Int) {
    CARRIER("carrier", R.string.carrier_section_title),
    SPEED("speed", R.string.speed_section_title),
    LOCATION("location", R.string.location_section_title),
    NR("nr", R.string.nr_section_title),
    LTE("lte", R.string.lte_section_title),
    NEIGHBORS("neighbors", R.string.neighbors_section_title),
    CELL_DETAIL("cellDetail", R.string.cell_detail_section_title),
    ;

    companion object {
        /** 默认顺序，就是枚举的声明顺序。 */
        val DEFAULT: List<CardKind> = entries.toList()

        private val byKey = entries.associateBy { it.key }

        fun fromKey(key: String): CardKind? = byKey[key]
    }
}

/**
 * 卡片顺序的存取。存 SharedPreferences，一个逗号分隔的 key 串。
 *
 * 用 SharedPreferences 而不是 DataStore：只有一个字符串要存，
 * 为此引入 datastore-preferences 依赖不值当。
 *
 * 读的时候对存下来的顺序做一次校验和补全——版本升级新增了卡片种类时，
 * 老用户存的串里没有它，直接用就会让新卡片消失。所以缺的补到末尾，
 * 不认识的（降级或手改）丢掉。
 */
object CardOrderStore {

    private const val PREFS_NAME = "card_order"
    private const val KEY_ORDER = "order"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读顺序。没存过、或存的内容坏了，都退回默认顺序。 */
    fun load(context: Context): List<CardKind> {
        val raw = prefs(context).getString(KEY_ORDER, null)
            ?: return CardKind.DEFAULT

        val saved = raw.split(',')
            .mapNotNull { CardKind.fromKey(it.trim()) }
            .distinct()

        if (saved.isEmpty()) return CardKind.DEFAULT

        // 新版本新增的卡片种类补到末尾，否则它会因为不在已存顺序里而不显示
        val missing = CardKind.DEFAULT.filter { it !in saved }
        return saved + missing
    }

    fun save(context: Context, order: List<CardKind>) {
        prefs(context).edit()
            .putString(KEY_ORDER, order.joinToString(",") { it.key })
            .apply()
    }

    fun reset(context: Context) {
        prefs(context).edit().remove(KEY_ORDER).apply()
    }
}
