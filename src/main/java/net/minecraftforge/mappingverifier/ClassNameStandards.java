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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.objectweb.asm.Opcodes;

import net.minecraftforge.srgutils.IMappingFile;

// New Class naming standard adopted in 1.14
// Interfaces prefixed with I
// Enums NOT prefixed with Enum
// Smurph Names moved to Suffix
public class ClassNameStandards extends SimpleVerifier {
    protected ClassNameStandards(MappingVerifier verifier) {
        super(verifier);
    }

    private static final boolean isNumber(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public boolean process() {
        IMappingFile o2m = verifier.getMappings();
        InheratanceMap inh = verifier.getInheratance();
        Map<String, String> suffixes = verifier.getSuffixes();

        inh.getRead().forEach(entry -> {
            List<String> errors = new ArrayList<>();

            //Set parents = [entry.name]

            String obf = entry.name;
            String mapped = o2m.remapClass(obf);
            int obfDepth = obf.split("\\$").length;
            int depth = mapped.split("\\$").length;
            if (obfDepth != depth)
                errors.add("Inner");
            else {
                if (obfDepth > 1) {
                    String obfParent = obf.substring(0, obf.lastIndexOf('$'));
                    String mapParent = o2m.remapClass(obfParent);
                    String parent = mapped.substring(0, mapped.lastIndexOf('$'));
                    if (!parent.equals(mapParent))
                        errors.add("Parent: " + mapParent);
                }
            }


            String name = mapped.substring(mapped.lastIndexOf('/') + 1);
            int idx = name.lastIndexOf('$');
            if (idx != -1)
                name = name.substring(idx + 1);

            if (!isNumber(name) && !obf.equals(mapped)) { //Skip anon classes
                boolean isInterface  = (entry.getAccess() & Opcodes.ACC_INTERFACE ) != 0;
                boolean isAbstract   = (entry.getAccess() & Opcodes.ACC_ABSTRACT  ) != 0;
                boolean isAnnotation = (entry.getAccess() & Opcodes.ACC_ANNOTATION) != 0;
                boolean isEnum       = (entry.getAccess() & Opcodes.ACC_ENUM      ) != 0;

                if (isInterface && !isAnnotation && name.charAt(0) != 'I')
                    errors.add("Interface");
                if (!isInterface && name.charAt(0) == 'I' && Character.isUpperCase(name.charAt(1))) {
                    if (!Arrays.asList("IPBanList", "IPBanEntry", "IOWorker").contains(name)) //Hardcode is bad
                        errors.add("Fake Interface");
                }
                if (isEnum && name.startsWith("Enum"))
                    errors.add("Enum");
                /* This is a SHOULD not a MUST, so I have this disabled.
                if (isAbstract && !isInterface && !isEnum && !name.startsWith('Abstract'))
                   errors.add("Abstract");
                */

                if ((name.contains("_") && !mapped.startsWith("net/minecraft/util/datafix/versions/")) || name.contains("C_"))
                    errors.add("Underscore");

                Set<String> parents = entry.getStack().stream().map(e -> o2m.remapClass(e.name)).collect(Collectors.toSet());
                if (suffixes != null && !suffixes.isEmpty()) {
                    for (String type : suffixes.keySet()) {
                        String suffix = suffixes.get(type);
                        if (parents.contains(type)) {
                            if (!name.endsWith(suffix))
                                errors.add("Suffix: " + suffix);
                            break; //Only the first applicable one so we dont get multiple suffixes for a deep child
                        }
                    }
                }
            }
            if (!errors.isEmpty())
                error("  %s %s", mapped, errors.stream().collect(Collectors.joining(" ")));
        });
        return !getErrors().isEmpty();
    }
}
