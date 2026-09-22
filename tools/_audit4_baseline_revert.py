"""第 4 轮审核的对照实验：把修复**逐条退回原样**，确认回归测试确实会失败。

为什么不能直接用 `git stash` 退回：回归测试需要一个接缝才能编译
（上限常量得是 `internal`、`extract` 得能注入上限、规则得是个纯函数），
而 git 退回会把接缝一起撤掉 —— 那样测试连编译都过不去，红的是编译器而不是断言，
对照实验就失去了意义。所以这里只退**行为**，接缝留着。

每处替换都做**必须命中**检查（MISS 即退出）：退不掉却不报错，
会让整个对照实验变成一场自欺 —— "以为退回了、其实没退"时测试照样全绿，
于是得出"测试不能发现这个缺陷"的错误结论。
"""
import os
import sys

BASE = "D:/MixiaVault-audit4-baseline"
VAULT = os.path.join(BASE, "app/src/main/java/com/fpb/vault/vault")

REVERTS = [
    # ---- 1. 备份恢复的两个上限：退回硬编码的旧值 ----
    (
        os.path.join(VAULT, "BackupManager.kt"),
        """    internal val MAX_ENTRY_BYTES: Long =
        ChunkedBlobFormat.headerOf(VaultSession.MAX_VIDEO_PLAINTEXT_BYTES).expectedStoredBytes()""",
        """    internal val MAX_ENTRY_BYTES: Long = 256L * 1024 * 1024 // [对照实验] 退回修复前：硬编码 256 MiB""",
    ),
    (
        os.path.join(VAULT, "BackupManager.kt"),
        """    internal val MAX_TOTAL_BYTES: Long =
        MAX_ENTRY_BYTES * (NotePayload.MAX_VIDEOS.toLong() * 2)""",
        """    internal val MAX_TOTAL_BYTES: Long = 4L * 1024 * 1024 * 1024 // [对照实验] 退回修复前：硬编码 4 GiB""",
    ),
    # ---- 2. 导入路由：退回"过大就按图片报错，不问 MIME" ----
    (
        os.path.join(VAULT, "MediaImportRoute.kt"),
        """        image is ImagePipeline.Preparation.TooLarge && mime == NoteType.IMAGE ->
            Plan.ImageTooLarge(image.message)""",
        """        image is ImagePipeline.Preparation.TooLarge ->
            Plan.ImageTooLarge(image.message) // [对照实验] 退回修复前：不问 MIME""",
    ),
]


def main():
    for path, old, new in REVERTS:
        with open(path, "r", encoding="utf-8") as fh:
            text = fh.read()
        if old not in text:
            print(f"[MISS] 没找到要退回的片段，对照实验无效：{path}")
            print("       片段首行：" + old.splitlines()[0])
            return 1
        if text.count(old) != 1:
            print(f"[MISS] 片段出现 {text.count(old)} 次，无法确定退哪一处：{path}")
            return 1
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(text.replace(old, new))
        print(f"[OK] 已退回：{os.path.basename(path)}  ←  {old.splitlines()[0].strip()}")

    print("\n共退回 %d 处。接缝（internal / 参数注入 / 纯函数）保持不动。" % len(REVERTS))
    return 0


if __name__ == "__main__":
    sys.exit(main())
