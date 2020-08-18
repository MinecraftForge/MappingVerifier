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

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.objectweb.asm.tree.ClassNode;

import net.minecraftforge.srgutils.IMappingFile;

public class Constructors extends SimpleVerifier {
    protected Constructors(MappingVerifier verifier) {
        super(verifier);
    }

    @Override
    public boolean process() {
        InheratanceMap inh = verifier.getInheratance();
        IMappingFile map = verifier.getMappings();
        Map<String, List<Integer>> ctrs = verifier.getCtrs();
        if (ctrs == null)
            return true; // No constructors loaded, dont check

        boolean success = inh.getRead()
        .sorted((o1, o2) -> o1.name.compareTo(o2.name))
        .map(cls -> {
            String clsName = map.remapClass(cls.name);
            Main.LOG.fine("  Processing: " + clsName);
            ClassNode node = inh.getNode(cls.name);

            if (node == null) {
                error("  Missing node: " + cls.name);
                return false; //Does this ever happen?
            }


            return node.methods.stream()
                .filter(m -> "<init>".equals(m.name) && !"()V".equals(m.desc))
                .map(mtd -> {
                    String desc = map.remapDescriptor(mtd.desc);
                    if (!ctrs.containsKey(clsName + desc)) {
                        error("    Missing Ctr: " + clsName + " " + desc);
                        return false;
                    }
                    return true;
                }).reduce(true, (a,b) -> a && b);
        }).reduce(true, (a,b) -> a && b);

        success = ctrs.entrySet().stream().filter(e -> e.getValue().size() > 1).map(e -> {
            error("    Duplicate Ctr: " + e.getKey() + " " + e.getValue().stream().map(Object::toString).collect(Collectors.joining(", ")));
            return false;
        }).reduce(true, (a,b) -> a && b) && success;

        return success;
    }
}
