package liruonian.jroutine.weave;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import liruonian.jroutine.Config;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SimpleVerifier;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.objectweb.asm.Opcodes.*;


/**
 * 分析方法中的变量和指令
 */
public class JroutineMethodAnalyzer extends MethodNode {

    private static final Logger logger = LoggerFactory.getLogger(JroutineMethodAnalyzer.class);

    private final String className;
    private final List<MethodInsnNode> probablyNewVars = new ArrayList<>();

    protected MethodVisitor mv;
    protected List<Label> preBuriedLabels = new ArrayList<>();
    protected List<MethodInsnNode> preBuriedNodes = new ArrayList<>();
    protected int contextVar;
    protected Analyzer<BasicValue> basicAnalyzer;
    protected List<AbstractInsnNode> endNodes = new ArrayList<>();

    public JroutineMethodAnalyzer(String className, MethodVisitor mv, int access, String name, String descriptor,
            String signature, String[] exceptions) {
        super(ASM8, access, name, descriptor, signature, exceptions);
        this.className = className;
        this.mv = mv;
    }

    @Override
    public void visitMethodInsn(int opcodeAndSource, String owner, String name, String descriptor,
            boolean isInterface) {
        if (api < ASM5 && (opcodeAndSource & SOURCE_DEPRECATED) == 0) {
            super.visitMethodInsn(opcodeAndSource, owner, name, descriptor, isInterface);
            return;
        }

        int opcode = opcodeAndSource & ~SOURCE_MASK;
        MethodInsnNode mn = new MethodInsnNode(opcode, owner, name, descriptor, isInterface);
        // INVOKESPECIAL指令 或 方法名为<init> 时，都可能涉及到创建对象，将这些指令记录下来，后续进行变量提升
        if (opcode == INVOKESPECIAL || "<init>".equals(name)) {
            probablyNewVars.add(mn);
        }

        // 是否为可挂起的指令
        if (isSuspendableInsn(opcode, name)) {
            Label label = new Label();
            super.visitLabel(label);
            preBuriedLabels.add(label);
            preBuriedNodes.add(mn);
        }

        instructions.add(mn);
    }
    
    @Override
    public void visitInsn(int opcode) {
        super.visitInsn(opcode);

        if (isEndInsn(opcode)) {
            endNodes.add(instructions.getLast());
        }
    }

    @Override
    public void visitEnd() {
        if (instructions.size() == 0 || preBuriedLabels.size() == 0) {
            accept(mv);
            return;
        }

        contextVar = maxLocals;

        try {
            HashMap<AbstractInsnNode, MethodInsnNode> promotableVars;
            if ((promotableVars = findPromotableVars()).size() > 0) {
                promoteVars(promotableVars);
            }

            basicAnalyzer = new Analyzer<BasicValue>(new SimpleVerifier()) {

                protected Frame<BasicValue> newFrame(final int nLocals, final int nStack) {
                    return new MonitoringFrame(nLocals, nStack);
                }

                protected Frame<BasicValue> newFrame(final Frame src) {
                    return new MonitoringFrame(src);
                }
            };
            basicAnalyzer.analyze(className, this);

            accept(new JroutineMethodAdapter(this));
        } catch (AnalyzerException e) {
            logger.error(e.getMessage(), e);
            e.printStackTrace();
            accept(mv);
        }
    }

    @Override
    protected LabelNode getLabelNode(Label label) {
        if (!(label.info instanceof LabelNode)) {
            label.info = new LabelNode(label);
        }
        return (LabelNode) label.info;
    }

    private HashMap<AbstractInsnNode, MethodInsnNode> findPromotableVars() throws AnalyzerException {
        HashMap<AbstractInsnNode, MethodInsnNode> promotableVars = new HashMap<>();

        Analyzer<SourceValue> sourceAnalyzer = new Analyzer<>(new SourceInterpreter());
        sourceAnalyzer.analyze(className, this);

        Frame<SourceValue>[] frames = sourceAnalyzer.getFrames();
        for (MethodInsnNode methodInsn: probablyNewVars) {
            Frame<SourceValue> frame = frames[instructions.indexOf(methodInsn)];
            Type[] args = Type.getArgumentTypes(methodInsn.desc);

            SourceValue sourceValue = frame.getStack(frame.getStackSize() - args.length - 1);
            Set<AbstractInsnNode> insns = sourceValue.insns;
            for (AbstractInsnNode insn : insns) {
                if (insn.getOpcode() == NEW) {
                    promotableVars.put(insn, methodInsn);
                } else {
                    if (insn.getOpcode() == DUP) {
                        AbstractInsnNode prevInsn = insn.getPrevious();
                        if (prevInsn.getOpcode() == NEW) {
                            promotableVars.put(prevInsn, methodInsn);
                        }
                    } else if (insn.getOpcode() == SWAP) {
                        AbstractInsnNode insn1 = insn.getPrevious();
                        AbstractInsnNode insn2 = insn1.getPrevious();
                        if (insn2.getOpcode() == NEW && insn1.getOpcode() == DUP_X1) {
                            promotableVars.put(insn2, methodInsn);
                        }
                    }
                }
            }
        }
        return promotableVars;
    }

    private void promoteVars(HashMap<AbstractInsnNode, MethodInsnNode> promotableVars) {
        int updateMaxStack = 0;
        for (Map.Entry<AbstractInsnNode, MethodInsnNode> entry : promotableVars.entrySet()) {
            AbstractInsnNode node1 = entry.getKey();
            AbstractInsnNode node2 = node1.getNext();
            AbstractInsnNode node3 = node2.getNext();

            boolean requireDup = false;
            instructions.remove(node1); // NEW
            if (node2.getOpcode() == DUP) {
                instructions.remove(node2); // DUP
                requireDup = true;
            } else if (node2.getOpcode() == DUP_X1) {
                instructions.remove(node2); // DUP_X1
                instructions.remove(node3); // SWAP
                requireDup = true;
            }

            MethodInsnNode mnode = entry.getValue();
            AbstractInsnNode mn = mnode;

            int varOffset = contextVar + 1;
            Type[] args = Type.getArgumentTypes(mnode.desc);

            if (args.length == 0) {
                InsnList doNew = new InsnList();
                doNew.add(node1);
                if (requireDup) {
                    doNew.add(new InsnNode(DUP));
                }
                instructions.insertBefore(mn, doNew);
                continue;
            }

            if (args.length == 1 && args[0].getSize() == 1) {
                InsnList doNew = new InsnList();
                doNew.add(node1);
                if (requireDup) {
                    doNew.add(new InsnNode(DUP));
                    doNew.add(new InsnNode(DUP2_X1));
                    doNew.add(new InsnNode(POP2));
                    updateMaxStack = Math.max(updateMaxStack, 2);
                } else {
                    doNew.add(new InsnNode(SWAP));
                }
                instructions.insertBefore(mn, doNew);
                continue;
            }

            if ((args.length == 1 && args[0].getSize() == 2)
                    || (args.length == 2 && args[0].getSize() == 1 && args[1].getSize() == 1)) {
                final InsnList doNew = new InsnList();
                doNew.add(node1);
                if (requireDup) {
                    doNew.add(new InsnNode(DUP));
                    doNew.add(new InsnNode(DUP2_X2));
                    doNew.add(new InsnNode(POP2));
                    updateMaxStack = Math.max(updateMaxStack, 2);
                } else {
                    doNew.add(new InsnNode(DUP_X2));
                    doNew.add(new InsnNode(POP));
                    updateMaxStack = Math.max(updateMaxStack, 1);
                }
                instructions.insertBefore(mn, doNew);
                continue;
            }

            InsnList doNew = new InsnList();
            for (int j = args.length - 1; j >= 0; j--) {
                Type type = args[j];

                doNew.add(new VarInsnNode(type.getOpcode(ISTORE), varOffset));
                varOffset += type.getSize();
            }
            maxLocals = Math.max(varOffset, maxLocals);

            doNew.add(node1);
            if (requireDup) {
                doNew.add(new InsnNode(DUP));
            }

            for (Type type: args) {
                varOffset -= type.getSize();

                doNew.add(new VarInsnNode(type.getOpcode(ILOAD), varOffset));

                if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) {
                    updateMaxStack = Math.max(updateMaxStack, 1);

                    doNew.add(new InsnNode(ACONST_NULL));
                    doNew.add(new VarInsnNode(type.getOpcode(ISTORE), varOffset));
                }
            }

            instructions.insertBefore(mn, doNew);
        }

        maxStack += updateMaxStack;
    }

    /**
     * 判断是否为可挂起的指令
     */
    private boolean isSuspendableInsn(int opcode, String name) {
        if (Config.getExtensionType() == ExtensionType.METHOD && isMethodInsn(opcode, name)) {
            return true;
        }
        return Config.getExtensionType() == ExtensionType.METHOD_AND_LOOP
                && (isMethodInsn(opcode, name) || isLoopInsn(opcode));
    }

    /**
     * 是否为RETURN相关指令
     */
    private boolean isEndInsn(int opcode) {
        return opcode >= IRETURN && opcode <= RETURN;
    }

    /**
     * 是否为方法执行相关指令
     */
    private boolean isMethodInsn(int opcode, String name) {
        return opcode == INVOKEINTERFACE || opcode == INVOKEVIRTUAL || opcode == INVOKESTATIC
                || (opcode == INVOKESPECIAL && !"<init>".equals(name));
    }

    /**
     * TODO 是否涉及循环
     */
    private boolean isLoopInsn(int opcode) {
        return opcode == -1;
    }

}
