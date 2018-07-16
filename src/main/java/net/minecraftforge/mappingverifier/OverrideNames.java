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

import org.objectweb.asm.Opcodes;

import net.minecraftforge.mappingverifier.InheratanceMap.Access;
import net.minecraftforge.mappingverifier.InheratanceMap.Class;
import net.minecraftforge.mappingverifier.InheratanceMap.Node;
import net.minecraftforge.mappingverifier.Mappings.ClsInfo;

public class OverrideNames implements IVerifier
{
    public static final OverrideNames INSTANCE = new OverrideNames();

    @Override
    public boolean process(InheratanceMap inh, Mappings map)
    {
        return inh.getRead()
        .sorted((o1, o2) -> o1.name.compareTo(o2.name))
        .reduce(true, (v, cls) ->
        {
            boolean failed = false;
            MappingVerifier.LOG.info("  Processing: " + map.map(cls.name));
            ClsInfo info = map.getClass(cls.name);
            failed = cls.fields.values().stream().sequential().sorted((o1, o2) -> o1.name.compareTo(o2.name)).map(entry ->
            {
                String newName = info.map(entry.name);

                Deque<Class> q = new ArrayDeque<>(cls.getStack());
                while (!q.isEmpty())
                {
                    Class parent = q.poll();
                    ClsInfo pinfo = map.getClass(parent.name);

                    Node f = parent.fields.get(pinfo.unmap(newName));
                    if (f != null && !Access.isPrivate(f.access))
                    {
                        /*
                        log("FD: %s/%s %s/%s #Shadow %s/%s",
                            e.name, entry.name,
                            map.map(e.name), newName,
                            map.map(parent.name), newName
                        );
                        */
                        log("    Shade: %s/%s -- %s", cls.name, entry.name, newName);
                        log("           %s/%s -- %s", map.map(parent.name), f.name, newName);
                        return false;
                    }
                    parent = parent.getParent();
                }
                return true;
            }).reduce(true, (a, b) -> a && b);


            failed |= cls.methods.values().stream().sequential().sorted((o1, o2) -> o1.name.equals(o2.name) ? o1.desc.compareTo(o2.desc) : o1.name.compareTo(o2.name)).map(mt ->
            {
                if (mt.name.startsWith("<") || ((mt.access & Opcodes.ACC_STATIC) != 0))
                    return true;

                String newName = map.getClass(cls.name).map(mt.name, mt.desc);
                String newSignature = map.mapDesc(mt.desc);

                Deque<Class> q = new ArrayDeque<>(cls.getStack());
                while (!q.isEmpty())
                {
                    Class parent = q.poll();
                    ClsInfo pinfo = map.getClass(parent.name);
                    String unmapped = pinfo.unmap(newName, newSignature);

                    Node m = parent.methods.get(unmapped + mt.desc);

                    if (m != null && !Access.isPrivate(m.access))
                    {
                        if (!mt.name.equals(unmapped))
                        {
                            /*
                            log("MD: %s/%s %s %s/%s %s #Shadow %s/%s",
                                e.name, node.name, node.desc,
                                map.map(e.name), newName, newSignature,
                                map.map(parent.name), newName
                                );
                            */
                            log("    Shade: %s/%s %s -- %s", cls.name, mt.name, mt.desc, newName);
                            log("           %s/%s %s -- %s", parent.name, unmapped, mt.desc, newName);
                            return false;
                        }
                    }

                    m = parent.methods.get(mt.name + mt.desc);
                    if (m != null && !Access.isPrivate(m.access))
                    {
                        String mapped = pinfo.map(mt.name, mt.desc);
                        if (!newName.equals(mapped))
                        {
                            /*
                            log("MD: %s/%s %s %s/%s %s #Override %s/%s ",
                                cls.name, mt.name, mt.desc,
                                map.map(cls.name), newName, newSignature,
                                map.map(parent.name), mapped
                            );
                            */
                            log("    Override: %s/%s %s -- %s -> %s", cls.name, mt.name, mt.desc, newName, mapped);
                            return false;
                        }
                    }
                }

                return true;
            }).reduce(true, (a, b) -> a && b);

            return failed;
        }, (a,b) -> a && b).booleanValue();
    }

    private void log(String format, String... args)
    {
        MappingVerifier.LOG.warning(String.format(format, (Object[])args));
    }
}
