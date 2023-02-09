/*
 * Mapping Verifier
 * Copyright (c) 2016-2020.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package net.minecraftforge.mappingverifier;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import static org.objectweb.asm.Opcodes.*;

public class InheratanceMap {
    private static final Handle LAMBDA_METAFACTORY = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",       "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
    private static final Handle LAMBDA_ALTMETAFACTORY = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "altMetafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;", false);

    private Map<String, Class> classes = new HashMap<>();
    private Map<String, ClassNode> nodes = new HashMap<>();
    private Map<String, Set<Method>> bouncers = new HashMap<>();
    private Map<String, Set<Method>> toResolveBouncers = new HashMap<>();

    public void processClass(InputStream data) throws IOException {
        ClassNode node = new ClassNode();
        ClassReader reader = new ClassReader(data);
        reader.accept(node, 0);

        Class cls = getClass(node.name);
        cls.parent = getClass(node.superName);
        cls.wasRead = true;
        cls.access = node.access;

        for (String intf : node.interfaces)
            cls.interfaces.add(getClass(intf));

        for (FieldNode n : node.fields)
            cls.fields.put(n.name, new Field(cls, n));


        //Gather Lambda methods so we can skip them in bouncers?
        Set<String> lambdas = new HashSet<>();
        for (MethodNode mtd : node.methods) {
            for (AbstractInsnNode asn : (Iterable<AbstractInsnNode>)() -> mtd.instructions.iterator()) {
                if (asn instanceof InvokeDynamicInsnNode) {
                    Handle target = getLambdaTarget((InvokeDynamicInsnNode)asn);
                    if (target != null) {
                        lambdas.add(target.getOwner() + '/' + target.getName() + target.getDesc());
                    }
                }
            }
        }

        for (MethodNode n : node.methods)
            cls.methods.put(n.name + n.desc, new Method(cls, n, lambdas.contains(node.name + '/' + n.name + n.desc)));

        for (Method m : cls.methods.values()) {
            if (m.isBouncer()) {
                if (cls.name.equals(m.bounce.owner)) {
                    Method target = cls.getMethod(m.bounce.name, m.bounce.desc);
                    if (target != null)
                        target.bouncers.add(m);
                } else if (classes.containsKey(m.bounce.owner)) {
                    addBouncer(classes.get(m.bounce.owner), m);
                } else {
                    bouncers.computeIfAbsent(m.bounce.owner, (name) -> new HashSet<>()).add(m);
                }
            }
        }
        
        for (Method m : bouncers.getOrDefault(cls.name, new HashSet<>())) {
            addBouncer(cls, m);
        }
        bouncers.remove(cls.name);

        this.nodes.put(node.name, node);
    }

    private void addBouncer(Class cls, Method m) {
        Class parent;
        for (parent = cls; parent != null && parent.wasRead(); parent = parent.getParent()) {
            Method target = parent.getMethod(m.bounce.name, m.bounce.desc);
            if (target != null) {
                target.bouncers.add(m);
                return;
            }
        }
        
        if (parent != null) {
            bouncers.computeIfAbsent(parent.name, (name) -> new HashSet<>()).add(m);
        }
    }

    private Handle getLambdaTarget(InvokeDynamicInsnNode idn) {
        if (LAMBDA_METAFACTORY.equals(idn.bsm)    && idn.bsmArgs != null && idn.bsmArgs.length == 3 && idn.bsmArgs[1] instanceof Handle)
            return ((Handle)idn.bsmArgs[1]);
        if (LAMBDA_ALTMETAFACTORY.equals(idn.bsm) && idn.bsmArgs != null && idn.bsmArgs.length == 5 && idn.bsmArgs[1] instanceof Handle)
            return ((Handle)idn.bsmArgs[1]);
        return null;
    }

    public Class getClass(String name) {
        return classes.computeIfAbsent(name, k -> new Class(name));
    }

    public ClassNode getNode(String name) {
        return nodes.get(name);
    }

    public Stream<Class> getRead() {
        return classes.values().stream().filter(e -> e.wasRead);
    }

    public void resolve() {
        classes.values().stream().forEach(this::resolve);
    }

    private void resolve(Class cls) {
        if (cls == null || cls.resolved)
            return;

        resolve(cls.getParent());
        cls.interfaces.forEach(this::resolve);
        Predicate<Method> canBeOverriden = mtd -> mtd.name.charAt(0) != '<' && (mtd.access & (Opcodes.ACC_FINAL | Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0;
        Predicate<Method> canOverride    = mtd -> mtd.name.charAt(0) != '<' && (mtd.access & (                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0;

        for (Method mtd : cls.methods.values()) {
            if (!canOverride.test(mtd))
                continue;

            for (Class parent : cls.getStack()) {
                Method pmtd = parent.getMethod(mtd.name, mtd.desc);
                if (pmtd != null && canBeOverriden.test(pmtd)) {
                    mtd.overrides.addAll(pmtd.getRoots(false));
                    break;
                }
            }
        }

        for (Method mtd : cls.methods.values()) {
            if (mtd.overrides.isEmpty() && !mtd.bouncers.isEmpty() && canOverride.test(mtd)) {
                for (Method bounce : mtd.bouncers) {
                    if (!bounce.overrides.isEmpty()) {
                        mtd.overrides.addAll(bounce.overrides);
                    } else if (!bounce.owner.resolved && bounce.owner != cls) {
                        toResolveBouncers.computeIfAbsent(bounce.getKey(), (name) -> new HashSet<>()).add(mtd);
                    }
                }
            }
            
            for (Method bounce : toResolveBouncers.getOrDefault(mtd.getKey(), new HashSet<>())) {
                if (!mtd.overrides.isEmpty()) {
                    bounce.overrides.addAll(mtd.overrides);
                }
            }
        }

        if (!cls.isAbstract()) {
            Map<String, Method> abs = new HashMap<>();
            List<Class> stack = new ArrayList<>(cls.getStack());
            stack.add(0, cls);
            stack.stream()
            .flatMap(c -> c.methods.values().stream())
            .filter(Node::isAbstract)
            .filter(mtd -> mtd.overrides.isEmpty())
            .forEach(mtd -> abs.put(mtd.name + mtd.desc, mtd));

            for (Class parent : stack) {
                for (Method mtd : parent.methods.values()) {
                    if (mtd.isAbstract())
                        continue;

                    Method target = abs.remove(mtd.name + mtd.desc);
                    if (target != null)
                        mtd.overrides.add(target);
                }
            }
        }

        cls.resolved = true;
    }

    public static class Class {
        private boolean resolved = false;
        private boolean wasRead = false;
        private int access = 0;
        private Class parent;
        public final String name;
        public final Map<String, Field> fields = new HashMap<>();
        public final Map<String, Method> methods = new HashMap<>();
        public final List<Class> interfaces = new ArrayList<>();
        private List<Class> stack = null;

        public Class(String name) {
            this.name = name;
        }

        public boolean wasRead() {
            return wasRead;
        }

        public int getAccess() {
            return access;
        }

        public boolean isAbstract() {
            return (getAccess() & ACC_ABSTRACT) != 0;
        }

        public Class getParent() {
            return parent;
        }

        @Override
        public String toString() {
            return this.name + " [" + fields.size() + ", " + methods.size() + "]";
        }

        public Field getField(String name) {
            return fields.get(name);
        }

        public Method getMethod(String name, String desc) {
            return methods.get(name + desc);
        }

        public List<Class> getStack() {
            if (stack == null) {
                Set<String> visited = new HashSet<>();

                stack = new ArrayList<>();

                Queue<Class> q = new ArrayDeque<>();
                if (parent != null)
                    q.add(parent);
                this.interfaces.forEach(q::add);

                while (!q.isEmpty()) {
                    Class cls = q.poll();
                    if (!visited.contains(cls.name)) {
                        stack.add(cls);
                        visited.add(cls.name);
                        if (cls.parent != null && !visited.contains(cls.parent.name))
                            q.add(cls.parent);

                        cls.interfaces.stream().filter(i -> !visited.contains(i.name)).forEach(q::add);
                    }
                }
            }
            return stack;
        }
    }

    public static class Node {
        public final Class owner;
        public final String name;
        public final String desc;
        public final int access;
        private final int hash;

        Node(Class owner, String name, String desc, int access) {
            this.owner = owner;
            this.name = name;
            this.desc = desc;
            this.access = access;
            this.hash = (name + desc).hashCode();
        }

        public int getAccess() {
            return this.access;
        }

        public boolean isAbstract() {
            return (getAccess() & ACC_ABSTRACT) != 0;
        }

        public String getKey() {
            return this.owner.name + "/" + this.name + this.desc;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public String toString() {
            return Access.get(this.access).name() + " " + this.owner.name + "/" + this.name + this.desc;
        }
    }

    public static class Field extends Node {
        Field(Class owner, FieldNode n) {
            super(owner, n.name, n.desc, n.access);
        }
    }

    public class Method extends Node {
        private final Bounce bounce;
        private final Set<Method> bouncers = new HashSet<>();
        private Set<Method> overrides = new HashSet<>();
        private Collection<Method> roots;

        Method(Class owner, MethodNode node, boolean lambda) {
            super(owner, node.name, node.desc, node.access);
            Bounce bounce = null;
            if (!lambda && (node.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0 && (node.access & Opcodes.ACC_STATIC) == 0) {
                AbstractInsnNode start = node.instructions.getFirst();
                if (start instanceof LabelNode && start.getNext() instanceof LineNumberNode)
                    start = start.getNext().getNext();

                if (start instanceof VarInsnNode) {
                    VarInsnNode n = (VarInsnNode)start;
                    if (n.var == 0 && n.getOpcode() == Opcodes.ALOAD) {
                        AbstractInsnNode end = node.instructions.getLast();
                        if (end instanceof LabelNode)
                            end = end.getPrevious();

                        if (end.getOpcode() >= Opcodes.IRETURN && end.getOpcode() <= Opcodes.RETURN)
                            end = end.getPrevious();

                        if (end instanceof MethodInsnNode) {
                            Type[] args = Type.getArgumentTypes(node.desc);
                            int var = 1;
                            int index = 0;
                            start = start.getNext();
                            while (start != end) {
                                if (start instanceof VarInsnNode) {
                                    if (((VarInsnNode)start).var != var || index + 1 > args.length) {
                                        //Arguments are switched around, so seems like lambda!
                                        end = null;
                                        break;
                                    }
                                    var += args[index++].getSize();
                                } else if (start.getOpcode() == Opcodes.INSTANCEOF || start.getOpcode() == Opcodes.CHECKCAST) {
                                    //Valid!
                                } else {
                                    // Anything else is invalid in a bouncer {As far as I know}, so we're most likely a lambda
                                    end = null;
                                    break;
                                }
                                start = start.getNext();
                            }

                            MethodInsnNode mtd = (MethodInsnNode)end;
                            if (end != null && Type.getArgumentsAndReturnSizes(node.desc) == Type.getArgumentsAndReturnSizes(mtd.desc))
                                bounce = new Bounce(mtd.owner, mtd.name, mtd.desc);
                        }
                    }
                }
            }
            this.bounce = bounce;
        }

        public boolean isBouncer() {
            return this.bounce != null;
        }

        public Set<Method> getBouncers() {
            return this.bouncers;
        }

        public Collection<Method> getRoots() {
            return getRoots(true);
        }

        private Collection<Method> getRoots(boolean resolveNested) {
            if (roots == null || !resolveNested) {
                Collection<InheratanceMap.Method> roots;
                if (this.overrides.isEmpty()) {
                    roots = Arrays.asList(this);
                } else {
                    roots = this.overrides;
                    while (resolveNested && roots.stream().anyMatch((mtd) -> !mtd.overrides.isEmpty())) {
                        roots = roots.stream().map(Method::getRoots).flatMap(Collection::stream).collect(Collectors.toSet());
                    }
                }
                if (resolveNested)
                    this.roots = roots;
                else
                    return roots;
            }
            return roots;
        }

        @SuppressWarnings("unused")
        private class Bounce {
            private final String owner;
            private final String name;
            private final String desc;

            private Bounce(String owner, String name, String desc) {
                this.owner = owner;
                this.name = name;
                this.desc = desc;
            }
        }
    }

    public static enum Access {
        PRIVATE, DEFAULT, PROTECTED, PUBLIC;
        public static Access get(int acc) {
            if ((acc & ACC_PRIVATE)   == ACC_PRIVATE  ) return PRIVATE;
            if ((acc & ACC_PROTECTED) == ACC_PROTECTED) return PROTECTED;
            if ((acc & ACC_PUBLIC)    == ACC_PUBLIC   ) return PUBLIC;
            return DEFAULT;
        }

        public static boolean isPrivate(int acc) {
            return get(acc) == PRIVATE;
        }
    }
}
