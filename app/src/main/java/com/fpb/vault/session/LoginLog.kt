package com.fpb.vault.session

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * 一条登录记录的类型。
 *
 * ## [code] 是落盘契约，不是序号
 *
 * 它会被写进密文里，因此**只许新增、不许重排**。重排的后果不会报错：
 * 老记录里 `2` 是"假密码"，重排后 `2` 变成别的含义 —— 用户会看到
 * 一条**凭空出现的假密码登录**，而且时间对得上、看起来完全真实。
 * 这类错读比读不出来危险得多，所以解码侧认不出 [code] 时一律判整段不可读
 * （见 [LoginLogCodec.decode]），而不是跳过或猜。
 */
enum class LoginKind(val code: Int, val label: String) {
    REAL_PASSWORD(1, "真密码"),
    DECOY_PASSWORD(2, "假密码"),
    RECOVERY_CODE(3, "恢复码"),
    BIOMETRIC(4, "生物识别"),

    /** 这个不是"一次登录"，是"在本次成功解锁之前，有人连续输错了 N 次"。 */
    FAILED_ATTEMPTS(5, "密码输错"),
    ;

    companion object {
        fun fromCode(code: Int): LoginKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * 一条登录记录。
 *
 * [detail] 目前只有 [LoginKind.FAILED_ATTEMPTS] 用它（输错的次数）；
 * 其余类型恒为 0。**不给每种类型各配一个可空字段**，是因为那会长出一堆
 * "只有某一种类型才有值"的字段，而解码时必须为每一种都定义"没有值时算什么"。
 */
class LoginEvent(
    val kind: LoginKind,
    val at: Long,
    val detail: Int = 0,
) {
    override fun equals(other: Any?): Boolean =
        other is LoginEvent && other.kind == kind && other.at == at && other.detail == detail

    override fun hashCode(): Int = (kind.code * 31 + at.toInt()) * 31 + detail

    override fun toString(): String = "LoginEvent(${kind.name}, $at, $detail)"
}

/**
 * 一台设备上的登录账本。
 *
 * 顺序约定：内部**按时间从旧到新**存放，因此"挤掉最旧的"就是从头截断。
 * 界面要倒序（最新在上）时用 [newestFirst]，不在存储层倒着存 ——
 * 倒存会让"追加"变成"插到最前面"，而截断点也跟着跑到另一头。
 *
 * 它是不可变的：每次追加返回新的一份。这样"解锁时记一笔"与"界面正在读的
 * 那一份"永远不会互相改到一半 —— 记录是在解锁流程里写的，而那一刻界面
 * 可能正拿着上一份列表在重组。
 */
class LoginLog private constructor(private val events: List<LoginEvent>) {

    val size: Int get() = events.size

    val isEmpty: Boolean get() = events.isEmpty()

    /** 是否已经因为上限而开始丢弃最旧的记录。界面据此说清"更早的看不到了"。 */
    val isTruncated: Boolean get() = events.size >= MAX_ENTRIES

    /** 追加一条，并把总数截到 [MAX_ENTRIES]。 */
    fun appended(event: LoginEvent): LoginLog {
        val next = events + event
        return LoginLog(if (next.size > MAX_ENTRIES) next.takeLast(MAX_ENTRIES) else next)
    }

    /** 把另一本账并进来（真库拿诱饵钥匙读假库那本账时用）。 */
    fun merged(other: LoginLog): LoginLog {
        if (other.isEmpty) return this
        if (isEmpty) return other
        val all = (events + other.events).sortedBy { it.at }
        return LoginLog(if (all.size > MAX_ENTRIES) all.takeLast(MAX_ENTRIES) else all)
    }

    /** 最新在前，交给界面直接渲染。 */
    fun newestFirst(): List<LoginEvent> = events.asReversed()

    fun toList(): List<LoginEvent> = events

    companion object {
        /**
         * 保留的条数上限。
         *
         * 200 条约 8 KiB，够看清"谁在什么时候进来过"。**不能设成无限**：
         * 这是个能被反复触发的写入（每次失败都记一笔），没有上限就意味着
         * 一个反复试密码的人能把库撑大。超限时挤掉的是**最旧**的一条，
         * 界面会用 [isTruncated] 明说"更早的已经看不到了" —— 静默丢历史
         * 与静默丢用户输入一样不能接受。
         */
        const val MAX_ENTRIES = 200

        val EMPTY: LoginLog = LoginLog(emptyList())

        fun of(events: List<LoginEvent>): LoginLog = LoginLog(events.sortedBy { it.at })
    }
}

/** 记录的展示文案。放在这一层而不是界面里，才能被单测钉住。 */
fun describe(event: LoginEvent): String = when (event.kind) {
    LoginKind.REAL_PASSWORD -> "用真密码打开保险库"
    LoginKind.DECOY_PASSWORD -> "用假密码打开 —— 进去的是那个诱饵库"
    LoginKind.RECOVERY_CODE -> "用恢复码打开保险库"
    LoginKind.BIOMETRIC -> "用指纹或人脸打开保险库"
    LoginKind.FAILED_ATTEMPTS -> "在这之前有 ${event.detail} 次密码输错"
}

/**
 * 把时刻说成人话：`今天 09:12` / `昨天 21:03` / `9月15日 08:44` / `2025年12月31日 23:50`。
 *
 * 用 [DateTimeFormatter] 而不是 `SimpleDateFormat`：后者不是线程安全的，
 * 而这里会在列表里被每一行各调一次。
 *
 * **"今天/昨天"必须按本地日历算，不能用 `now - at < 24 小时` 这种减法。**
 * 那样算出来的结果是错的，而且错得很自然：今晚 23:50 登录、第二天 00:10 来看，
 * 只差 20 分钟，减法会说"今天"，而用户正过着新的一天 ——
 * 他会以为记录写错了，或者以为自己昨晚没进来过。
 *
 * 时间戳在未来（用户改过系统时间）时按"同一天"处理，不给它贴任何特殊标签：
 * 那是时钟问题，不是记录问题。
 */
fun loginTimeLabel(
    at: Long,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val moment = Instant.ofEpochMilli(at).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(moment.toLocalDate(), today)
    val time = TIME_FORMAT.format(moment)
    return when {
        days == 0L -> "今天 $time"
        days == 1L -> "昨天 $time"
        today.year == moment.year -> "${MONTH_DAY_FORMAT.format(moment)} $time"
        else -> "${FULL_DATE_FORMAT.format(moment)} $time"
    }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
private val MONTH_DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
private val FULL_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)

/**
 * 登录账本的编解码器。
 *
 * ## 格式（v1，大端序）
 *
 * ```
 * magic   4 字节  "MXL1"
 * version 1 字节  = 1
 * count   4 字节
 * events  count × (1 字节 kind + 8 字节 at + 4 字节 detail)
 * ```
 *
 * ## 为什么解码失败返回 null 而不是空列表
 *
 * 与 [com.fpb.vault.codec.NoteIndexCodec] 同一条理由，但后果更隐蔽：
 * 返回空列表的话，"这段记录读不出来"会被显示成"**还没有任何登录记录**"。
 * 用户看到的是"一切正常、没人进过库"，而真相可能是"有人进来过，
 * 而且把记录弄坏了"。**在一个专门用来发现入侵的功能里，把读不出来
 * 说成没发生过，是最坏的一种失败。** 所以宁可让界面明说"读不出来"。
 */
object LoginLogCodec {

    const val SCHEMA_VERSION = 1

    private val MAGIC = byteArrayOf(0x4D, 0x58, 0x4C, 0x31) // "MXL1"

    /** 单条记录 13 字节。用于算一个合理的分配上限，别让一个被改过的头撑爆内存。 */
    private const val BYTES_PER_EVENT = 13

    fun encode(log: LoginLog): ByteArray {
        val events = log.toList()
        // 与解码侧同源同律：解码会拒绝的形状，编码就不许产出。
        require(events.size <= LoginLog.MAX_ENTRIES) {
            "登录记录 ${events.size} 条超过上限 ${LoginLog.MAX_ENTRIES}，拒绝写出"
        }
        val out = ByteArrayOutputStream(9 + events.size * BYTES_PER_EVENT)
        DataOutputStream(out).use { d ->
            d.write(MAGIC)
            d.writeByte(SCHEMA_VERSION)
            d.writeInt(events.size)
            events.forEach { e ->
                d.writeByte(e.kind.code)
                d.writeLong(e.at)
                d.writeInt(e.detail)
            }
        }
        return out.toByteArray()
    }

    /**
     * @return 账本；格式不合规、或出现认不出的类型码时返回 `null`。
     *
     * 它**不销毁**传进来的字节：调用方拿到的是解密出来的明文，销毁由调用方
     * 按既有口径自己负责（见 `VaultSession` 里 `openRow` 的用法）——
     * 在一读就丢的 `decode` 里顺手销毁，会让"这段明文还能不能用"变得靠猜。
     */
    fun decode(bytes: ByteArray): LoginLog? = try {
        decodeOrThrow(bytes)
    } catch (e: Exception) {
        null
    }

    private fun decodeOrThrow(bytes: ByteArray): LoginLog? {
        val input = DataInputStream(ByteArrayInputStream(bytes))

        val magic = ByteArray(MAGIC.size)
        input.readFully(magic)
        if (!magic.contentEquals(MAGIC)) return null

        if (input.readUnsignedByte() != SCHEMA_VERSION) return null

        val count = input.readInt()
        if (count < 0 || count > LoginLog.MAX_ENTRIES) return null

        val events = ArrayList<LoginEvent>(count)
        repeat(count) {
            val kind = LoginKind.fromCode(input.readUnsignedByte()) ?: return null
            val at = input.readLong()
            val detail = input.readInt()
            events.add(LoginEvent(kind, at, detail))
        }

        if (input.available() != 0) return null
        return LoginLog.of(events)
    }
}
