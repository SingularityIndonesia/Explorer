import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode

/**
 * Debug builds only: every function in the app's own classes reports its start and end to the
 * in-app profiler (`profiler.CallTrace`, in the debug source set), which counts and times the calls.
 * Release builds are left exactly as compiled.
 */
class CallTracePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.withPlugin("com.android.application") {
            val components = project.extensions.getByType(AndroidComponentsExtension::class.java)
            components.onVariants(components.selector().withBuildType("debug")) { variant ->
                variant.instrumentation.transformClassesWith(CallTraceFactory::class.java, InstrumentationScope.PROJECT) {
                    it.packagePrefix.set(PACKAGE)
                    it.recorder.set(RECORDER)
                }
                // The try/finally added around each body needs new stack map frames.
                variant.instrumentation.setAsmFramesComputationMode(FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS)
            }
        }
    }

    private companion object {
        const val PACKAGE = "com.singularity_universe.explorer"
        const val RECORDER = "$PACKAGE.profiler.CallTrace"
    }
}

interface CallTraceParameters : InstrumentationParameters {
    /** Classes in this package (and below) are traced. */
    @get:Input val packagePrefix: Property<String>

    /** The class receiving the calls; its package (the profiler itself) isn't traced. */
    @get:Input val recorder: Property<String>
}

abstract class CallTraceFactory : AsmClassVisitorFactory<CallTraceParameters> {
    override fun isInstrumentable(classData: ClassData): Boolean {
        val name = classData.className
        val profilerPackage = parameters.get().recorder.get().substringBeforeLast('.') + "."
        return name.startsWith(parameters.get().packagePrefix.get() + ".") && !name.startsWith(profilerPackage)
    }

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor {
        val className = classContext.currentClassData.className
        val shortName = className.removePrefix(parameters.get().packagePrefix.get() + ".")
        return CallTraceClassVisitor(nextClassVisitor, shortName, parameters.get().recorder.get().replace('.', '/'))
    }
}

private class CallTraceClassVisitor(
    next: ClassVisitor,
    private val className: String,
    private val recorder: String,
) : ClassVisitor(Opcodes.ASM9, next) {
    override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<String>?): MethodVisitor? {
        val next = super.visitMethod(access, name, descriptor, signature, exceptions) ?: return null
        val skip = access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE or Opcodes.ACC_BRIDGE) != 0 ||
            // Constructors can't be wrapped before super() runs; accessors and default-argument stubs only forward.
            name == "<init>" || name == "<clinit>" ||
            (access and Opcodes.ACC_SYNTHETIC != 0 && !name.contains("lambda"))
        if (skip) return next
        // Read the whole body first, to leave out trivial ones (getters, setters, constants).
        return object : MethodNode(Opcodes.ASM9, access, name, descriptor, signature, exceptions) {
            override fun visitEnd() {
                super.visitEnd()
                accept(if (isTrivial(this)) next else TraceMethodVisitor(next, "$className.$name", recorder))
            }
        }
    }

    /** No calls and only a handful of instructions: tracing it would cost more than it runs. */
    private fun isTrivial(method: MethodNode): Boolean {
        var count = 0
        for (insn in method.instructions) {
            if (insn is MethodInsnNode || insn is InvokeDynamicInsnNode) return false
            if (insn.opcode >= 0) count++
        }
        return count <= 12
    }
}

/** Calls `enter(name)` at the start, and `exit(name)` at every return and when an exception escapes. */
private class TraceMethodVisitor(
    next: MethodVisitor,
    private val traceName: String,
    private val recorder: String,
) : MethodVisitor(Opcodes.ASM9, next) {
    private val start = Label()
    private val end = Label()
    private val handler = Label()

    override fun visitCode() {
        super.visitCode()
        call("enter")
        super.visitLabel(start)
    }

    override fun visitInsn(opcode: Int) {
        if (opcode in Opcodes.IRETURN..Opcodes.RETURN) call("exit")
        super.visitInsn(opcode)
    }

    override fun visitMaxs(maxStack: Int, maxLocals: Int) {
        super.visitLabel(end)
        // Registered last, so the method's own try/catch blocks still get their exceptions first.
        super.visitTryCatchBlock(start, end, handler, null)
        super.visitLabel(handler)
        call("exit")
        super.visitInsn(Opcodes.ATHROW)
        super.visitMaxs(maxStack, maxLocals)
    }

    private fun call(method: String) {
        super.visitLdcInsn(traceName)
        super.visitMethodInsn(Opcodes.INVOKESTATIC, recorder, method, "(Ljava/lang/String;)V", false)
    }
}
