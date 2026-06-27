package com.gtocutcorners.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * JVM Agent - 在类加载前拦截并修改字节码。
 * 用法: -javaagent:gtocutcorners-agent.jar
 */
public class RecipeAgent {

    public static void premain(String args, Instrumentation inst) {
        System.out.println("[GTOCutCorners Agent] premain() called - installing transformer");

        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className,
                                    Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain,
                                    byte[] classfileBuffer) {

                // 只拦截 GasCompressor
                if (!"com/gtocore/data/recipe/classified/GasCompressor".equals(className)) {
                    return null; // 不修改
                }

                System.out.println("[GTOCutCorners Agent] Intercepted: " + className);
                return patchGasCompressor(classfileBuffer);
            }
        }, true);

        System.out.println("[GTOCutCorners Agent] Transformer installed");
    }

    private static byte[] patchGasCompressor(byte[] bytes) {
        try {
            ClassReader cr = new ClassReader(bytes);
            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);

            boolean patched = false;
            for (MethodNode mn : cn.methods) {
                if ("init".equals(mn.name) && "()V".equals(mn.desc)) {
                    System.out.println("[GTOCutCorners Agent] Found init()V, "
                        + mn.instructions.size() + " instructions");
                    int count = 0;
                    var iter = mn.instructions.iterator();
                    while (iter.hasNext()) {
                        var insn = iter.next();
                        if (insn.getOpcode() == Opcodes.SIPUSH) {
                            var nxt = insn.getNext();
                            if (nxt != null && nxt.getOpcode() == Opcodes.INVOKEVIRTUAL
                                && "duration".equals(((MethodInsnNode) nxt).name)
                                && "com/gtolib/api/recipe/RecipeBuilder"
                                    .equals(((MethodInsnNode) nxt).owner)) {
                                count++;
                                int val = ((IntInsnNode) insn).operand;
                                System.out.println("[GTOCutCorners Agent] duration() #"
                                    + count + " val=" + val);
                                if (count == 1) {
                                    mn.instructions.insertBefore(insn,
                                        new InsnNode(Opcodes.ICONST_1));
                                    mn.instructions.remove(insn);
                                    patched = true;
                                    System.out.println(
                                        "[GTOCutCorners Agent] >>> PATCHED: duration("
                                        + val + ") -> 1 tick");
                                }
                            }
                        }
                    }
                }
            }

            if (!patched) {
                System.out.println("[GTOCutCorners Agent] WARNING: no patch applied");
                return bytes;
            }

            ClassWriter cw = new ClassWriter(
                ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            cn.accept(cw);
            return cw.toByteArray();
        } catch (Exception e) {
            System.err.println("[GTOCutCorners Agent] Error: " + e.getMessage());
            return bytes;
        }
    }
}
