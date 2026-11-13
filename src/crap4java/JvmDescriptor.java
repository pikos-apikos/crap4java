package crap4java;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

final class JvmDescriptor {
    private JvmDescriptor() {
    }

    /** Returns null when any erased signature type cannot be resolved. */
    static String of(ExecutableElement method, Types types, Elements elements) {
        StringBuilder descriptor = new StringBuilder("(");
        for (var parameter : method.getParameters()) {
            String type = typeDescriptor(types.erasure(parameter.asType()), elements);
            if (type == null) {
                return null;
            }
            descriptor.append(type);
        }
        String result = typeDescriptor(types.erasure(method.getReturnType()), elements);
        return result == null ? null : descriptor.append(')').append(result).toString();
    }

    private static String typeDescriptor(TypeMirror type, Elements elements) {
        return switch (type.getKind()) {
            case BOOLEAN -> "Z";
            case BYTE -> "B";
            case SHORT -> "S";
            case INT -> "I";
            case LONG -> "J";
            case CHAR -> "C";
            case FLOAT -> "F";
            case DOUBLE -> "D";
            case VOID -> "V";
            case ARRAY -> {
                String component = typeDescriptor(((ArrayType) type).getComponentType(), elements);
                yield component == null ? null : "[" + component;
            }
            case DECLARED -> "L" + elements.getBinaryName(
                    (TypeElement) ((DeclaredType) type).asElement()).toString().replace('.', '/') + ";";
            // In particular, never guess the binary name of an ERROR type.
            default -> null;
        };
    }
}
