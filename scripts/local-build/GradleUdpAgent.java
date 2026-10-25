package peygirbuild;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;

/** Optional Windows build-only workaround for an exhausted ephemeral UDP range.
 * Changes only Gradle 8.11.1's cache lock communicator to a free explicit port.
 * Cache locking, OS networking settings and the Android app are unchanged.
 */
public final class GradleUdpAgent {
    public static void premain(String args, Instrumentation instrumentation) {
        try {instrumentation.appendToBootstrapClassLoaderSearch(new java.util.jar.JarFile(args));}
        catch(java.io.IOException e) {throw new IllegalStateException(e);}
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader,String name,Class<?> redef,ProtectionDomain domain,byte[] bytes) {
                if (!"org/gradle/cache/internal/locklistener/FileLockCommunicator".equals(name)) return null;
                ClassReader reader=new ClassReader(bytes);
                ClassWriter writer=new ClassWriter(0);
                reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
                    @Override public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions) {
                        MethodVisitor mv=super.visitMethod(access,name,desc,signature,exceptions);
                        if (!"<init>".equals(name)) return mv;
                        return new MethodVisitor(Opcodes.ASM9,mv) {
                            @Override public void visitInsn(int opcode) {
                                if(opcode==Opcodes.ICONST_0) super.visitMethodInsn(Opcodes.INVOKESTATIC,"peygirbuild/LocalUdpPort","nextPort","()I",false);
                                else super.visitInsn(opcode);
                            }
                        };
                    }
                },0);
                return writer.toByteArray();
            }
        });
    }
}
