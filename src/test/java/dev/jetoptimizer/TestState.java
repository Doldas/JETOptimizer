package dev.jetoptimizer;

import net.neoforged.neoforge.common.ModConfigSpec;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Isolated loader/config inputs for headless tests; production code has no test-only switches. */
final class TestState implements AutoCloseable {
    private final List<Runnable> undo = new ArrayList<>();
    static Field field(Class<?> type, String name) throws Exception {
        while (type != null) {
            try { var field = type.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException absent) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }
    static Object get(Class<?> type, String name) throws Exception { return field(type, name).get(null); }
    void set(Class<?> type, String name, Object value) throws Exception { set(field(type, name), null, value); }
    void config(ModConfigSpec.ConfigValue<?> option, Object value) throws Exception {
        set(field(option.getClass(), "cachedValue"), option, value);
    }
    private void set(Field field, Object target, Object value) throws Exception {
        Object original = field.get(target);
        undo.add(() -> { try { field.set(target, original); } catch (Exception e) { throw new AssertionError(e); } });
        field.set(target, value);
    }
    @Override public void close() { undo.reversed().forEach(Runnable::run); }
}
