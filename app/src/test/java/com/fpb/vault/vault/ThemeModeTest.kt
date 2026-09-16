package com.fpb.vault.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外观模式三档的取值契约。
 *
 * 这些断言看着琐碎，但它们守的是**升级路径**：`theme_mode` 这个偏好是明文存在 SharedPreferences 里的，
 * 用户手里那个值在应用升级、降级、被别的版本改过之后未必还是我们写进去的三个字面量之一。
 * 一旦 [ThemeMode.normalize] 漏掉某种脏数据，配色就会退化成"未定义"——
 * 而配色退化的表现是黑底黑字，用户看到的是"应用坏了"，不是"设置项有问题"。
 * 所以凡是"从磁盘读出来的、不是编译期常量"的取值，都必须先过一遍这里。
 */
class ThemeModeTest {

    @Test
    fun `三个合法取值原样通过`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.normalize(ThemeMode.SYSTEM))
        assertEquals(ThemeMode.LIGHT, ThemeMode.normalize(ThemeMode.LIGHT))
        assertEquals(ThemeMode.DARK, ThemeMode.normalize(ThemeMode.DARK))
    }

    @Test
    fun `空值与脏数据一律收敛到跟随系统`() {
        val dirty: List<String?> = listOf(
            null,                 // 首次安装，偏好吧里根本没有这个 key
            "",                   // 被写成空串
            " ",                  // 被写成空格
            "SYSTEM",             // 大小写不一致
            "Dark",               // 大小写不一致
            "auto",               // 别的版本用过的词
            "true",               // 曾经是布尔开关的遗留值
            "system ",            // 尾部空格
            "深夜",               // 人类语言混进来
            "3",                  // 曾经用序号存过
            "-1",
        )
        for (bad in dirty) {
            assertEquals(
                "脏值 ${bad?.let { "\"$it\"" } ?: "null"} 必须收敛到跟随系统",
                ThemeMode.SYSTEM,
                ThemeMode.normalize(bad),
            )
        }
    }

    @Test
    fun `每个常量都能在选项表里找到，且标签非空`() {
        for (value in listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)) {
            val option = ThemeMode.OPTIONS.firstOrNull { it.first == value }
            assertTrue("选项表里缺少 $value —— 加新档位时忘了同步 OPTIONS", option != null)
            assertFalse("$value 的展示名是空的，设置页会显示成一行空白", option!!.second.isBlank())
        }
        // 反向也要成立：选项表里不能有多余的、常量里没有的值。
        assertEquals(3, ThemeMode.OPTIONS.size)
    }

    @Test
    fun `选项表里的每个值都是稳定字面量，不会被误判成脏数据`() {
        // 这条防的是"OPTIONS 里写了带空格/大写，于是被自己的 normalize 收敛掉"——
        // 那会让某一档在设置页里选得上、却永远存不下来。
        for ((value, label) in ThemeMode.OPTIONS) {
            assertEquals("$label($value) 被 normalize 改写了", value, ThemeMode.normalize(value))
            assertEquals("$label 的标签取不回来", label, ThemeMode.labelOf(value))
        }
    }

    @Test
    fun `labelOf 对未知取值给一个能读的兜底`() {
        assertEquals("跟随系统", ThemeMode.labelOf(""))
        assertEquals("跟随系统", ThemeMode.labelOf("nonsense"))
        // 兜底不能是空串，否则设置页那一行的右半边会空着，像没加载出来。
        for (bad in listOf("", "nonsense", "LIGHT")) {
            assertTrue(ThemeMode.labelOf(bad).isNotBlank())
        }
    }

    @Test
    fun `存进去再读出来不变形（模拟设置页选一次的动作）`() {
        // SettingsStore 的 setter 也会过一遍 normalize，所以这里模拟"用户选的 → 落盘的字面量 → 读回"。
        for ((value, _) in ThemeMode.OPTIONS) {
            val written = ThemeMode.normalize(value)
            assertEquals(value, written)
            assertEquals(value, ThemeMode.normalize(written))
        }
    }
}
