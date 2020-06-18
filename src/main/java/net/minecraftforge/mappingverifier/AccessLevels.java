/*
 * Mapping Verifier
 * Copyright (c) 2016-2018.
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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import net.minecraftforge.mappingverifier.InheratanceMap.Class;
import net.minecraftforge.mappingverifier.InheratanceMap.Node;

public class AccessLevels extends SimpleVerifier
{
    protected AccessLevels(MappingVerifier verifier)
    {
        super(verifier);
    }

    @Override
    public boolean process()
    {
        InheratanceMap inh = verifier.getInheratance();
        Mappings map = verifier.getMappings();
        return inh.getRead()
        .sorted((o1, o2) -> o1.name.compareTo(o2.name))
        .map(cls ->
        {
            Main.LOG.fine("  Processing: " + map.map(cls.name));
            ClassNode node = inh.getNode(cls.name);

            if (node == null)
            {
                error("  Missing node: " + cls.name);
                return false; //Does this ever happen?
            }

            Set<String> warned = new HashSet<>();

            String newCls = map.map(cls.name);
            String pkg = packageName(newCls);
            boolean success = node.methods.stream().sequential().sorted((o1, o2) -> o1.name.equals(o2.name) ? o1.desc.compareTo(o2.desc) : o1.name.compareTo(o2.name)).map(mt ->
            {
                boolean inner_success = true;

                for (AbstractInsnNode isn : mt.instructions.toArray())
                {
                    if (isn instanceof FieldInsnNode)
                    {
                        FieldInsnNode field = (FieldInsnNode)isn;

                        boolean isSelf = field.owner.equals(node.name);

                        if (isSelf) //We can access anything in ourself
                            continue;

                        Class owner = inh.getClass(field.owner);
                        if (!owner.wasRead()) //If it wasn't read, we don't have the access levels, so we can't check anything, just assume its right.
                            continue;

                        Node target = findNode(owner, c -> c.getField(field.name)); //Include desc?
                        if (target == null) //We can't find it in the inheritance tree... So not in our reobfed code, assume correct.
                        {
                            /*
                            String newOwner = map.map(field.owner);
                            String newField = map.getClass(field.owner).map(field.name);
                            log("    Invalid target: %s/%s %s", newCls, newName, newSignature);
                            log("      %s: %s/%s", Printer.OPCODES[isn.getOpcode()], newOwner, newField);
                            */
                            continue;
                        }

                        String newOwner = map.map(target.owner.name);
                        String newField = map.getClass(target.owner.name).map(field.name);

                        boolean isPackage = pkg.equals(packageName(newOwner));
                        boolean isSubclass = cls.getStack().contains(target.owner);

                        inner_success &= canAccess(newCls, newOwner + "/" + newField, target.access, isPackage, isSubclass, isSelf, warned);
                    }
                    else if (isn instanceof MethodInsnNode)
                    {
                        MethodInsnNode method = (MethodInsnNode)isn;

                        boolean isSelf = method.owner.equals(node.name);
                        if (isSelf) //We can access anything in ourself
                            continue;

                        Class owner = inh.getClass(method.owner);
                        if (!owner.wasRead()) //If it wasn't read, we don't have the access levels, no do we have inheritance, so we can't check anything, just assume its right.
                            continue;

                        Node target = findNode(owner, c -> c.getMethod(method.name, method.desc));
                        String newDesc = map.mapDesc(method.desc);

                        if (target == null) //We can't find it in the inheritance tree... So not in our reobfed code, assume correct.
                        {
                            /*
                            String newOwner = map.map(method.owner);
                            String newMethod = map.getClass(method.owner).map(method.name, method.desc);
                            log("    Invalid target: %s/%s %s", newCls, newName, newSignature);
                            log("      %s: %s/%s %s", Printer.OPCODES[isn.getOpcode()], newOwner, newMethod, newDesc);
                            */
                            continue;
                        }

                        String newOwner = map.map(target.owner.name);
                        String newMethod = map.getClass(target.owner.name).map(method.name, method.desc);

                        boolean isPackage = pkg.equals(packageName(newOwner));
                        boolean isSubclass = cls.getStack().contains(target.owner);
                        inner_success &= canAccess(newCls, newOwner + "/" + newMethod + newDesc, target.access, isPackage, isSubclass, isSelf, warned);
                    }
                    else if (isn instanceof TypeInsnNode)
                    {
                        String obfed = ((TypeInsnNode)isn).desc;
                        boolean isSelf = obfed.equals(node.name);

                        if (isSelf) //We can access anything in ourself
                            continue;

                        Class owner = inh.getClass(obfed);
                        if (!owner.wasRead()) //If it wasn't read, we don't have the access levels, so we can't check anything, just assume its right.
                            continue;

                        String newOwner = map.map(obfed);
                        boolean isPackage = pkg.equals(packageName(newOwner));
                        boolean isSubclass = cls.getStack().contains(inh.getClass(obfed));
                        inner_success &= canAccess(newCls, newOwner, owner.getAccess(), isPackage, isSubclass, isSelf, warned);
                    }
                }

                return inner_success;
            }).reduce(true, (a,b) -> a && b);

            return success;
        }).reduce(true, (a,b) -> a && b);
    }

    private String packageName(String clsName)
    {
        int idx = clsName.lastIndexOf('/');
        return idx == -1 ? "" : clsName.substring(0, idx);
    }

    private boolean canAccess(String source, String target, int access, boolean isPackage, boolean isSubclass, boolean isSelf, Set<String> warned)
    {
        String key = source  + " -> " + target;
        if (warned.contains(key))
            return false;

        if ((access & Opcodes.ACC_PUBLIC) != 0)
        {
            return true; //Public anyone can access;
        }
        else if ((access & Opcodes.ACC_PROTECTED) != 0)
        {
            if (!isPackage && !isSubclass)
            {
                warned.add(key);
                error("    Invalid Access: %s -> %s PROTECTED", source, target);
                return false;
            }
        }
        else if ((access & Opcodes.ACC_PRIVATE) != 0)
        {
            if (!isSelf)
            {
                warned.add(key);
                error("    Invalid Access: %s -> %s PRIVATE", source, target);
                return false;
            }
        }
        else //default modifier, we need this as a else, cuz it has no flag
        {
            if (!isSelf && !isPackage)
            {
                warned.add(key);
                error("    Invalid Access: %s -> %s DEFAULT", source, target);
                return false;
            }
        }
        return true;
    }

    private Node findNode(Class start, Function<Class, Node> func)
    {
        Deque<Class> q = new ArrayDeque<>();
        q.add(start);
        q.addAll(start.getStack());

        Node target = null;
        while (!q.isEmpty() && target == null)
        {
            target = func.apply(q.pop());
        }

        return target;
    }
}
