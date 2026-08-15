package com.campusdeal.seckill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lua 脚本单元测试（LU-01..04）
 *
 * <p>不依赖真实 Redis：用 LuaJ 纯 Java Lua 解释器执行 <b>真实的 seckill.lua</b>，
 * 通过模拟的 redis.call 表读写内存态，验证脚本的原子扣减与去重逻辑。</p>
 */
class SeckillLuaScriptTest {

    /** 模拟 Redis String 存储 */
    private Map<String, String> stringStore;
    /** 模拟 Redis Set 存储 */
    private Map<String, Set<String>> setStore;
    private String script;

    @BeforeEach
    void setUp() throws IOException {
        stringStore = new HashMap<>();
        setStore = new HashMap<>();
        script = new String(
                new ClassPathResource("seckill.lua").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }

    /**
     * 在 LuaJ 中执行 seckill.lua，传入 dealId/userId 到 ARGV，返回脚本返回值。
     */
    private long runLua(String dealId, String userId) {
        Globals globals = JsePlatform.standardGlobals();

        // 模拟 redis.call(command, ...args)
        LuaTable redis = new LuaTable();
        redis.set("call", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String cmd = args.arg(1).tojstring();
                switch (cmd) {
                    case "get": {
                        String v = stringStore.get(args.arg(2).tojstring());
                        return v == null ? LuaValue.NIL : LuaValue.valueOf(v);
                    }
                    case "incrby": {
                        String key = args.arg(2).tojstring();
                        long delta = args.arg(3).tolong();
                        long nv = Long.parseLong(stringStore.getOrDefault(key, "0")) + delta;
                        stringStore.put(key, Long.toString(nv));
                        return LuaValue.valueOf(nv);
                    }
                    case "sismember":
                        return LuaValue.valueOf(
                                setStore.getOrDefault(args.arg(2).tojstring(), Set.of())
                                        .contains(args.arg(3).tojstring()) ? 1 : 0);
                    case "sadd":
                        setStore.computeIfAbsent(args.arg(2).tojstring(), x -> new LinkedHashSet<>())
                                .add(args.arg(3).tojstring());
                        return LuaValue.ONE;
                    default:
                        throw new UnsupportedOperationException("Unsupported redis command: " + cmd);
                }
            }
        });
        globals.set("redis", redis);

        // Redis 会把 ARGV 注入脚本环境，这里模拟
        LuaTable argv = new LuaTable();
        argv.set(1, LuaValue.valueOf(dealId));
        argv.set(2, LuaValue.valueOf(userId));
        globals.set("ARGV", argv);

        LuaValue chunk = globals.load(new StringReader(script), "seckill.lua");
        return chunk.call().tolong();
    }

    @Test
    @DisplayName("LU-01: 正常秒杀返回剩余库存（T16：>=0 = 成功，值为剩余库存）")
    void normalSeckill() {
        stringStore.put("flashdeal:stock:101", "100");

        assertThat(runLua("101", "1001")).isEqualTo(99);
    }

    @Test
    @DisplayName("LU-02: 库存不足返回 -1")
    void outOfStock() {
        stringStore.put("flashdeal:stock:101", "0");

        assertThat(runLua("101", "1001")).isEqualTo(-1);
    }

    @Test
    @DisplayName("LU-03: 重复下单返回 -2")
    void duplicateOrder() {
        stringStore.put("flashdeal:stock:101", "100");
        setStore.computeIfAbsent("flashdeal:order:101", x -> new LinkedHashSet<>()).add("1001");

        assertThat(runLua("101", "1001")).isEqualTo(-2);
    }

    @Test
    @DisplayName("LU-04: 最后一件库存扣减为 0，返回剩余 0")
    void lastStock() {
        stringStore.put("flashdeal:stock:101", "1");

        assertThat(runLua("101", "1001")).isZero();
        // 扣减后库存为 0，且用户进入下单集合
        assertThat(stringStore.get("flashdeal:stock:101")).isEqualTo("0");
        assertThat(setStore.get("flashdeal:order:101")).contains("1001");
    }
}
