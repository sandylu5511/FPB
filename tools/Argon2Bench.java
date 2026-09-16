import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * Argon2id 性能基准（开发期工具，位于 app 模块外，不会被打进 APK）。
 *
 * 用途：确定 KDF 参数时不能靠猜。BouncyCastle 是纯 Java 实现，
 * 必须先在真实机器上量出耗时，再决定 m/t/p 取值。
 *
 * 运行方式见 tools/README.md。
 */
public class Argon2Bench {

    public static void main(String[] args) {
        System.out.println("Argon2id (BouncyCastle 1.77, 纯 Java)  —— 单次 32 字节派生耗时");
        System.out.println("----------------------------------------------------------------");
        bench("32 MiB / t2 / p1", 32, 2, 1);
        bench("64 MiB / t3 / p2", 64, 3, 2);
        bench("64 MiB / t3 / p4", 64, 3, 4);
        bench("128 MiB / t4 / p2", 128, 4, 2);
        bench("256 MiB / t4 / p2", 256, 4, 2);
        System.out.println("----------------------------------------------------------------");
        System.out.printf("可用堆内存上限：%d MiB%n", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        System.out.println("可用处理器核心数：" + Runtime.getRuntime().availableProcessors());
    }

    private static void bench(String label, int memMiB, int iterations, int parallelism) {
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(new byte[16])
                .withMemoryAsKB(memMiB * 1024)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .build();

        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(params);
        byte[] out = new byte[32];

        // 预热，避免把 JIT 编译时间算进结果
        generator.generateBytes("warmup".toCharArray(), out);

        int runs = 3;
        long total = 0;
        long best = Long.MAX_VALUE;
        for (int i = 0; i < runs; i++) {
            long t0 = System.nanoTime();
            generator.generateBytes(("correct horse battery staple " + i).toCharArray(), out);
            long dt = System.nanoTime() - t0;
            total += dt;
            best = Math.min(best, dt);
        }
        System.out.printf("%-18s 平均 %7.0f ms   最快 %7.0f ms%n",
                label, total / runs / 1e6, best / 1e6);
    }
}
