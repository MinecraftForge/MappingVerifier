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

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.objectweb.asm.Type;

import net.minecraftforge.mappingverifier.Mappings.ClsInfo;

public class UniqueIDs extends SimpleVerifier
{
    protected UniqueIDs(MappingVerifier verifier)
    {
        super(verifier);
    }

    @Override
    public boolean process()
    {
        InheratanceMap inh = verifier.getInheratance();
        Mappings map = verifier.getMappings();
        Map<Integer, Set<String>> claimed = new HashMap<>();
        Map<String, Set<List<String>>> signatures = new HashMap<>();

        inh.getRead().forEach(cls ->
        {
            ClsInfo info = map.getClass(cls.name);
            Consumer<String[]> gather = entry ->
            {
                String idstr = entry[0].split("_")[1];
                if (idstr.matches("\\d+"))
                {
                    claimed.computeIfAbsent(Integer.parseInt(idstr), k -> new HashSet<>()).add(entry[0]);
                    signatures.computeIfAbsent(entry[0], k -> new HashSet<>()).add(Arrays.asList(Arrays.copyOfRange(entry, 1, entry.length)));
                }
            };

            cls.fields.values().stream()
            .map(field -> new String[]{info.map(field.name), field.name})
            .filter(entry -> entry[0].startsWith("field_"))
            .forEach(gather);

            cls.methods.values().stream()
            .map(method  -> new String[] {info.map(method.name, method.desc), method.name, method.desc})
            .filter(entry -> entry[0].startsWith("func_"))
            .forEach(gather);
        });

        Map<String, List<Integer>> ctrs = verifier.getCtrs();
        if (ctrs != null)
        {
            ctrs.values().stream().flatMap(List::stream).forEach(id -> claimed.computeIfAbsent(id, k -> new HashSet<>()).add(id.toString()));
        }

        return claimed.entrySet().stream().filter(e -> e.getValue().size() > 1 || different(signatures.get(e.getValue().iterator().next()))).sorted((e1, e2) -> e1.getKey().compareTo(e2.getKey())).map(entry ->
        {
            error("Duplicate ID: %s (%s)", entry.getKey().toString(), String.join(", ", entry.getValue()));
            entry.getValue().forEach(name ->
            {
                Set<List<String>> sigs = signatures.get(name);
                error("    %s (%s)", name, sigs == null ? "null" : sigs.stream().map(e -> String.join(" ", e)).collect(Collectors.joining(", ")));
            });
            return false;
        }).reduce(true, (a,b)-> a && b);
    }

    private boolean different(Set<List<String>> entries) {
        if (entries == null || entries.size() == 1)
            return false;

        boolean field = false, method = false;
        String name = null;
        int size = -1;
        Type ret = null;
        Type[] args = null;

        for (List<String> pts : entries)
        {

            if (name == null)
                name = pts.get(0);
            //else if (!name.equals(pts.get(0))) //Synthetic bouncer methods with different return types have different names...
            //    return true;

            if (pts.size() == 1)
            {
                if (field) return true;
                field = true;
            }
            else if (pts.size() == 2)
            {
                method = true;
                String desc = pts.get(1);
                if (size == -1)
                {
                    size = Type.getArgumentsAndReturnSizes(desc);
                    ret = Type.getReturnType(desc);
                    args = Type.getArgumentTypes(desc);
                }
                else
                {
                    if (size != Type.getArgumentsAndReturnSizes(desc) ||
                       !sameType(ret, Type.getReturnType(desc)))
                        return true;

                    Type[] _args = Type.getArgumentTypes(desc);
                    if (_args.length != args.length) return true;
                    for (int x = 0; x < args.length; x++)
                        if (!sameType(args[x], _args[x])) return true;
                }
            }
        }

        if (field && method)
            return true;

        return false;
    }

    private boolean sameType(Type a, Type b)
    {
        if (a.getSort() != Type.OBJECT && a.getSort() != Type.ARRAY)
            return a.getSort() == b.getSort();

        if (a.getSort() == Type.ARRAY)
            return b.getSort() == Type.ARRAY && a.getDimensions() == b.getDimensions() && sameType(a.getElementType(), b.getElementType());

        return a.getSort() == Type.OBJECT && b.getSort() == Type.OBJECT; //Due to overrides in generic classes having changed class names, lets just assume they are the same if they are objects.
    }
}
