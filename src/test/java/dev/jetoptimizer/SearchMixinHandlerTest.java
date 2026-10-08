package dev.jetoptimizer;

import net.minecraft.ChatFormatting;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.lang.reflect.Method;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the actual compiled static hook bodies; does not apply a mixin to a client target. */
@Tag("mixin-handler")
class SearchMixinHandlerTest {
    private static final class HookLoader extends ClassLoader {
        HookLoader() { super(SearchMixinHandlerTest.class.getClassLoader()); }
        Class<?> hook(String simpleName) throws Exception {
            String name="dev.jetoptimizer.mixin."+simpleName;
            try(var input=getParent().getResourceAsStream(name.replace('.','/')+".class")) {
                assertNotNull(input); byte[] bytes=input.readAllBytes();
                // A separate loader executes bytecode without ModLauncher's prohibition on loading
                // mixin implementation classes directly. All service/callback types use the parent.
                return defineClass(name,bytes,0,bytes.length);
            }
        }
    }
    static Method hook(String mixin,String name,Class<?>... parameters) throws Exception {
        var method=new HookLoader().hook(mixin).getDeclaredMethod(name,parameters);
        method.setAccessible(true); return method;
    }
    @Test void formattingHeadCancelsWithMinecraftEquivalentResult() throws Exception {
        try(var state=new TestState()) {
            state.set(SearchTextOptimization.class,"cachedEnabled",true);
            var method=hook("StringUtilMixin","jetoptimizer$fastStripChatFormatting",String.class,CallbackInfoReturnable.class);
            for(String text:List.of("§aCopper §Lwire§r","§a","§z§","日本語 😀")) {
                var callback=new CallbackInfoReturnable<String>("removeChatFormatting",true);
                method.invoke(null,text,callback);
                assertTrue(callback.isCancelled()); assertEquals(ChatFormatting.stripFormatting(text),callback.getReturnValue());
            }
        }
    }
    @Test void formattingHeadPreservesOriginalExecutionWhenDisabledOrInputIsNull() throws Exception {
        try(var state=new TestState()) {
            var method=hook("StringUtilMixin","jetoptimizer$fastStripChatFormatting",String.class,CallbackInfoReturnable.class);
            state.set(SearchTextOptimization.class,"cachedEnabled",false);
            var disabled=new CallbackInfoReturnable<String>("removeChatFormatting",true,"original");
            method.invoke(null,"§aCopper",disabled); assertFalse(disabled.isCancelled()); assertEquals("original",disabled.getReturnValue());
            state.set(SearchTextOptimization.class,"cachedEnabled",true);
            var nullInput=new CallbackInfoReturnable<String>("removeChatFormatting",true);
            method.invoke(null,null,nullInput); assertFalse(nullInput.isCancelled());
        }
    }
    @Test void whitespaceHeadAddsNativeTokensThenCancelsTheOriginalBody() throws Exception {
        try(var state=new TestState()) {
            state.set(SearchTextOptimization.class,"cachedEnabled",true);
            var method=hook("ListElementInfoMixin","jetoptimizer$fastSplitWhitespace",Set.class,String.class,CallbackInfo.class);
            var result=new LinkedHashSet<>(List.of("existing")); var expected=new LinkedHashSet<>(result);
            String text=" \tCopper\u00a0wire  iron\nCopper \u0000";
            Arrays.stream(text.trim().split("\\s+")).filter(s -> !s.isEmpty()).forEach(expected::add);
            var callback=new CallbackInfo("addSplitStrings",true); method.invoke(null,result,text,callback);
            assertTrue(callback.isCancelled()); assertEquals(new ArrayList<>(expected),new ArrayList<>(result));
        }
    }
    @Test void whitespaceHeadDoesNotMutateOrCancelOnDisabledOrNullInput() throws Exception {
        try(var state=new TestState()) {
            var method=hook("ListElementInfoMixin","jetoptimizer$fastSplitWhitespace",Set.class,String.class,CallbackInfo.class);
            var result=new LinkedHashSet<>(List.of("existing"));
            state.set(SearchTextOptimization.class,"cachedEnabled",false);
            var disabled=new CallbackInfo("addSplitStrings",true); method.invoke(null,result,"copper",disabled);
            assertFalse(disabled.isCancelled()); assertEquals(Set.of("existing"),result);
            state.set(SearchTextOptimization.class,"cachedEnabled",true);
            var nullInput=new CallbackInfo("addSplitStrings",true); method.invoke(null,result,null,nullInput);
            assertFalse(nullInput.isCancelled()); assertEquals(Set.of("existing"),result);
        }
    }
}
