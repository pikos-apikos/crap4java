package crap4java;

/**
 * A binary class name and a JVM method name/descriptor, independent of source lines.
 * The descriptor is null only for unresolved source signatures, which cannot match coverage.
 */
record MethodIdentity(String className, String methodName, String descriptor) {
}
