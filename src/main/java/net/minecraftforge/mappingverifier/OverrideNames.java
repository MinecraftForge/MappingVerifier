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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.objectweb.asm.Opcodes;

import net.minecraftforge.mappingverifier.InheratanceMap.Access;
import net.minecraftforge.mappingverifier.InheratanceMap.Class;
import net.minecraftforge.mappingverifier.InheratanceMap.Node;
import net.minecraftforge.mappingverifier.Mappings.ClsInfo;

public class OverrideNames extends SimpleVerifier
{
    @Override
    public boolean process(InheratanceMap inh, Mappings map)
    {
        return checkNormal(inh, map) && checkInterfaces(inh, map);
    }

    // This one we check every method defined in a class, walking its parent tree.
    // Catches simple subclasses who define the override.
    private boolean checkNormal(InheratanceMap inh, Mappings map)
    {
        return inh.getRead()
        .sorted((o1, o2) -> o1.name.compareTo(o2.name))
        .map(cls ->
        {
            boolean success = true;
            Main.LOG.info("  Processing: " + map.map(cls.name));
            ClsInfo info = map.getClass(cls.name);
            success &= cls.fields.values().stream().sequential().sorted((o1, o2) -> o1.name.compareTo(o2.name))
            .filter(field -> (field.access & Opcodes.ACC_STATIC) == 0)
            .map(entry ->
            {
                String newName = info.map(entry.name);

                return cls.getStack().stream().map(parent ->
                {
                    ClsInfo pinfo = map.getClass(parent.name);

                    Node f = parent.fields.get(pinfo.unmap(newName));
                    if (f != null && !Access.isPrivate(f.access))
                    {
                        error("    Shade: %s/%s %s/%s %s", cls.name, entry.name, map.map(parent.name), f.name, newName);
                        return false;
                    }
                    parent = parent.getParent();
                    return true;
                }).reduce(true, (a, b) -> a && b);
            }).reduce(true, (a, b) -> a && b);


            success &= cls.methods.values().stream().sequential().sorted((o1, o2) -> o1.name.equals(o2.name) ? o1.desc.compareTo(o2.desc) : o1.name.compareTo(o2.name))
            .filter(mt -> (mt.access & Opcodes.ACC_STATIC) == 0 && !mt.name.startsWith("<"))
            .map(mt ->
            {
                String newName = map.getClass(cls.name).map(mt.name, mt.desc);
                String newSignature = map.mapDesc(mt.desc);

                return cls.getStack().stream().map(parent ->
                {
                    ClsInfo pinfo = map.getClass(parent.name);
                    String unmapped = pinfo.unmap(newName, newSignature);

                    Node m = parent.methods.get(unmapped + mt.desc);

                    if (m != null && !Access.isPrivate(m.access))
                    {
                        if (!mt.name.equals(unmapped))
                        {
                            error("    Shade: %s/%s %s/%s %s %s", cls.name, mt.name, parent.name, unmapped, mt.desc, newName);
                            return false;
                        }
                    }

                    m = parent.methods.get(mt.name + mt.desc);
                    if (m != null && !Access.isPrivate(m.access))
                    {
                        String mapped = pinfo.map(mt.name, mt.desc);
                        if (!newName.equals(mapped))
                        {
                            error("    Override: %s/%s %s -- %s -> %s", cls.name, mt.name, mt.desc, newName, mapped);
                            return false;
                        }
                    }
                    return true;
                }).reduce(true, (a, b) -> a && b);
            }).reduce(true, (a, b) -> a && b);

            return success;
        }).reduce(true, (a,b) -> a && b);
    }


    /*
     * Here we check interfaces specifically, because there are edge cases where a class will implement a
     * interface, and have it's super-classes satisfy that interface's method.
     *
     * Example:
     *
     * class Foo {
     *   int getCount(){ return 0; }
     * }
     * interface ICountable {
     *   int getCount();
     * }
     * class Bar extends Foo implements ICountable {}
     */
    private boolean checkInterfaces(InheratanceMap inh, Mappings map)
    {
        Map<String, Set<String>> interfaces = inh.getRead().filter(e -> (e.getAccess() & Opcodes.ACC_INTERFACE) != 0).collect(Collectors.toMap(e -> e.name, e -> new HashSet<>()));
        inh.getRead().forEach(e -> e.interfaces.stream().map(i -> i.name).filter(i -> interfaces.containsKey(i)).forEach(i -> interfaces.get(i).add(e.name)));

        return interfaces.entrySet().stream().map(e ->
        {
            Main.LOG.info("  Processing Interface: " + map.map(e.getKey()));
            Set<String> fullStack = new HashSet<>(e.getValue());
            e.getValue().stream().map(inh::getClass).forEach(child -> child.getStack().stream().map(parent -> parent.name).forEach(fullStack::add));
            List<Class> stack = fullStack.stream().map(inh::getClass).collect(Collectors.toList());

            Class cls = inh.getClass(e.getKey());

            return cls.methods.values().stream().sequential().sorted((o1, o2) -> o1.name.equals(o2.name) ? o1.desc.compareTo(o2.desc) : o1.name.compareTo(o2.name))
            .filter(mt -> (mt.access & Opcodes.ACC_STATIC) == 0 && !mt.name.startsWith("<"))
            .map(mt ->
            {
                String newName = map.getClass(cls.name).map(mt.name, mt.desc);
                return stack.stream().map(parent ->
                {
                    if (map.map(e.getKey()).equals("net/minecraft/entity/IRangedAttackMob") && "s(Z)V".equals(mt.name + mt.desc))
                        System.currentTimeMillis();
                    ClsInfo pinfo = map.getClass(parent.name);

                    Node m = parent.getMethod(mt.name, mt.desc);

                    if (m != null && !Access.isPrivate(m.access))
                    {
                        String mapped = pinfo.map(mt.name, mt.desc);
                        if (!newName.equals(mapped))
                        {
                            error("    Override: %s/%s %s -- %s -> %s", parent.name, mt.name, mt.desc, mapped, newName);
                            return false;
                        }
                    }
                    return true;
                }).reduce(true, (a, b) -> a && b);
            }).reduce(true, (a, b) -> a && b);
        }).reduce(true, (a,b) -> a && b);
    }
}
