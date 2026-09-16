# -*- coding: utf-8 -*-
"""对照实验：在基线副本中逐条退回第三轮审核的修复。

每处替换都必须命中，否则脚本报错退出 —— 防止"以为退回了实际没退"，
让对照实验失去意义。模式均取自真实源文件**逐字内容**。

与第二轮（revert_round2_fixes.py）的区别：本轮退回的是"新增的判据/上限"，
因此对应的探测测试都必须由绿转红；其中 #4（桌面别名自愈）没有 JVM 判据，
一并退回只为让基线在代码上与修复前一致，它的验证只能靠真机（见审核报告）。
"""
import io
import sys

BASE = r"D:\MixiaVault-baseline3"

REVERTS = {
    # 1) 「有内容却从未导出过」不再提醒 —— 退回旧的"没记录过就当成刚备份过"
    r"\app\src\main\java\com\fpb\vault\vault\BackupReminder.kt": [(
        """        // 有内容却从没导出过：立刻提醒，不设任何"基线"。
        if (lastExportedAt <= 0L) return Due.NEVER_EXPORTED
""",
        """        if (lastExportedAt <= 0L) return Due.NONE
""",
    )],
    # 2) 读取不再边读边封顶，退回"全部读完再判断大小"
    r"\app\src\main\java\com\fpb\vault\vault\Streams.kt": [(
        """        total += read
        // 先把"越界"判掉再写：否则最后一小段超限的字节也已经进了内存。
        if (total > limit) return null
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
""",
        """        total += read
        out.write(buffer, 0, read)
    }
    if (total > limit) return null
    return out.toByteArray()
""",
    )],
    # 3) 备份包清单退回无上限读取
    r"\app\src\main\java\com\fpb\vault\vault\BackupManager.kt": [(
        """                        val text = zip.readCapped(MAX_MANIFEST_BYTES)?.toString(Charsets.UTF_8)
                            ?: throw BackupException("备份包的清单异常，无法确认格式")
""",
        """                        val text = zip.readBytes().toString(Charsets.UTF_8)
""",
    )],
    # 4) 别名启用状态退回"只认 ENABLED，把 DEFAULT 当成 DISABLED"
    r"\app\src\main\java\com\fpb\vault\vault\LauncherIcon.kt": [(
        """    fun isEnabled(context: Context, alias: String): Boolean {
        val pm = context.packageManager
        val component = ComponentName(context, alias)
        return when (pm.getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            // DISABLED_USER（用户在系统设置里关的）与 DISABLED_UNTIL_USED 同样是"不启用"。
            // 少列一个就会把"被用户手动停用"当成 DEFAULT 从而误判为启用。
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
            -> false
            // DEFAULT：读清单里那个值。取不到（理论上只在清单被改坏的版本上发生）
            // 就按"只有 FPB 这个明写 enabled=true 的别名是启用的"处理。
            else -> runCatching { pm.getActivityInfo(component, 0).enabled }
                .getOrDefault(alias == ALIAS_FPB)
        }
    }
""",
        """    fun isEnabled(context: Context, alias: String): Boolean =
        context.packageManager.getComponentEnabledSetting(ComponentName(context, alias)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
""",
    )],
}

for rel, pairs in REVERTS.items():
    path = BASE + rel
    with io.open(path, "r", encoding="utf-8") as f:
        content = f.read()
    for old, new in pairs:
        if old not in content:
            print("REVERT MISS: %s <<<%s>>>" % (path, old[:70]))
            sys.exit(1)
        content = content.replace(old, new, 1)
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(content)
    print("reverted:", rel)

print("ALL REVERTS APPLIED")
