package dev.jetoptimizer;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** Checks hooks against dependency bytecode without pretending that the dedicated-server runner applies client mixins. */
@Tag("mixin-contract")
class MixinContractTest {
    static ClassNode read(String name) throws IOException {
        try(var input=MixinContractTest.class.getClassLoader().getResourceAsStream(name.replace('.','/')+".class")) {
            assertNotNull(input,"Missing target bytecode: "+name);
            var node=new ClassNode(); new ClassReader(input).accept(node,0); return node;
        }
    }
    static List<AnnotationNode> annotations(List<AnnotationNode> visible,List<AnnotationNode> invisible) {
        var all=new ArrayList<AnnotationNode>(); if(visible!=null)all.addAll(visible); if(invisible!=null)all.addAll(invisible); return all;
    }
    static Object value(AnnotationNode annotation,String key,Object fallback) {
        if(annotation.values!=null) for(int i=0;i<annotation.values.size();i+=2)
            if(key.equals(annotation.values.get(i)))return annotation.values.get(i+1);
        return fallback;
    }
    static AnnotationNode annotation(ClassNode node,String suffix) {
        return annotations(node.visibleAnnotations,node.invisibleAnnotations).stream().filter(a -> a.desc.endsWith(suffix+";")).findFirst().orElseThrow();
    }
    static List<?> list(Object value) { return value instanceof List<?> values ? values : List.of(value); }
    @TestFactory Stream<DynamicTest> jeiAndMinecraftHooksResolveAgainstPinnedDependencies() throws Exception {
        try(var input=getClass().getResourceAsStream("/jetoptimizer.mixins.json")) {
            assertNotNull(input);
            var config=JsonParser.parseReader(new InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            return config.getAsJsonArray("client").asList().stream().map(e -> e.getAsString())
                .filter(name -> !name.equals("KubeJSJEIPluginMixin"))
                .map(name -> DynamicTest.dynamicTest(name,() -> verify(read("dev.jetoptimizer.mixin."+name))));
        }
    }
    @Test void optionalKubeJsHooksAreExplicitlyPseudoAndDoNotRequireTheMod() throws Exception {
        var mixin=read("dev.jetoptimizer.mixin.KubeJSJEIPluginMixin");
        assertNotNull(annotation(mixin,"/Pseudo"));
        // KubeJS is not a project dependency; no claim of integration coverage for its absent bytecode.
        assertFalse((Boolean)value(annotation(mixin,"/Mixin"),"remap",true));
    }
    static void verify(ClassNode mixin) throws Exception {
        var targets=list(value(annotation(mixin,"/Mixin"),"targets",List.of()));
        for(var targetName:targets) {
            var target=read((String)targetName);
            for(var field:mixin.fields) {
                boolean shadow=annotations(field.visibleAnnotations,field.invisibleAnnotations).stream().anyMatch(a -> a.desc.endsWith("/Shadow;"));
                if(shadow)assertTrue(hasField(target,field.name,field.desc),target.name+" no longer has shadow "+field.name+field.desc);
            }
            for(var handler:mixin.methods) for(var injection:annotations(handler.visibleAnnotations,handler.invisibleAnnotations)) {
                if(!Set.of("Inject","Redirect","WrapMethod","WrapOperation").stream().anyMatch(s -> injection.desc.endsWith("/"+s+";")))continue;
                for(var selector:list(value(injection,"method",List.of()))) {
                    String method=(String)selector; int descriptor=method.indexOf('(');
                    var matches=target.methods.stream().filter(m -> descriptor<0 ? m.name.equals(method) : (m.name+m.desc).equals(method)).toList();
                    assertFalse(matches.isEmpty(),mixin.name+"::"+handler.name+" missing method "+method+" on "+target.name);
                    for(var atValue:list(value(injection,"at",List.of()))) {
                        var at=(AnnotationNode)atValue; String kind=(String)value(at,"value","");
                        String invoked=(String)value(at,"target",""); int ordinal=(Integer)value(at,"ordinal",-1);
                        if(invoked.isEmpty())continue;
                        long count=matches.stream().flatMap(m -> Arrays.stream(m.instructions.toArray())).filter(insn -> matches(insn,kind,invoked)).count();
                        assertTrue(count>Math.max(ordinal,0),mixin.name+"::"+handler.name+" missing "+kind+" "+invoked+" ordinal "+ordinal);
                    }
                }
            }
        }
    }
    static boolean hasField(ClassNode node,String name,String descriptor) throws IOException {
        if(node.fields.stream().anyMatch(f -> f.name.equals(name)&&f.desc.equals(descriptor)))return true;
        return node.superName!=null && !node.superName.equals("java/lang/Object") && hasField(read(node.superName),name,descriptor);
    }
    static boolean matches(AbstractInsnNode instruction,String kind,String selector) {
        if(kind.startsWith("INVOKE") && instruction instanceof MethodInsnNode call)
            return selector.equals("L"+call.owner+";"+call.name+call.desc);
        if(kind.equals("NEW") && instruction instanceof TypeInsnNode type && type.getOpcode()==Opcodes.NEW)
            return selector.equals("L"+type.desc+";") || selector.equals(type.desc);
        if(kind.equals("FIELD") && instruction instanceof FieldInsnNode field)
            return selector.equals("L"+field.owner+";"+field.name+":"+field.desc);
        return false;
    }
}
