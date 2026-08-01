/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Architectural guard: HPPC types must never appear on any {@code public} or
 * {@code protected} API surface of the runtime. Production code may use HPPC
 * only as a private implementation detail (typically behind package-private
 * wrappers that expose JDK interfaces such as {@link java.util.Set} or
 * {@link java.util.concurrent.ConcurrentMap}).
 *
 * <p>Scans every {@code org.antlr.v4.runtime*} class visible on the test
 * classpath and fails if a public/protected type, method, constructor, or
 * field signature references {@code com.carrotsearch.hppc} or the shaded
 * relocation package.</p>
 */
public class TestHppcApiBoundary {

	private static final String[] HPPC_PREFIXES = {
		"com.carrotsearch.hppc",
		"org.antlr.v4.runtime.shaded.com.carrotsearch.hppc",
	};

	private static final String RUNTIME_PACKAGE_PREFIX = "org.antlr.v4.runtime";

	@Test
	public void publicAndProtectedApisNeverExposeHppcTypes() throws Exception {
		List<Class<?>> classes = loadRuntimeClasses();
		assertTrue("expected runtime classes on classpath", !classes.isEmpty());

		List<String> violations = new ArrayList<String>();
		for (Class<?> clazz : classes) {
			int classMods = clazz.getModifiers();
			// Public top-level or nested types must not extend/implement HPPC.
			if (Modifier.isPublic(classMods) || Modifier.isProtected(classMods)) {
				checkType("class " + clazz.getName() + " extends", clazz.getGenericSuperclass(), violations);
				for (Type iface : clazz.getGenericInterfaces()) {
					checkType("class " + clazz.getName() + " implements", iface, violations);
				}
			}

			for (Field field : clazz.getDeclaredFields()) {
				int m = field.getModifiers();
				if (Modifier.isPublic(m) || Modifier.isProtected(m)) {
					checkType(clazz.getName() + "#" + field.getName() + " field",
						field.getGenericType(), violations);
				}
			}

			for (Method method : clazz.getDeclaredMethods()) {
				int m = method.getModifiers();
				if (Modifier.isPublic(m) || Modifier.isProtected(m)) {
					String base = clazz.getName() + "#" + method.getName();
					checkType(base + " return", method.getGenericReturnType(), violations);
					Type[] params = method.getGenericParameterTypes();
					for (int i = 0; i < params.length; i++) {
						checkType(base + " param" + i, params[i], violations);
					}
					for (Type ex : method.getGenericExceptionTypes()) {
						checkType(base + " throws", ex, violations);
					}
				}
			}

			for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
				int m = ctor.getModifiers();
				if (Modifier.isPublic(m) || Modifier.isProtected(m)) {
					String base = clazz.getName() + "#<init>";
					Type[] params = ctor.getGenericParameterTypes();
					for (int i = 0; i < params.length; i++) {
						checkType(base + " param" + i, params[i], violations);
					}
				}
			}
		}

		if (!violations.isEmpty()) {
			StringBuilder sb = new StringBuilder();
			sb.append("HPPC types leaked into public/protected API:\n");
			for (String v : violations) {
				sb.append("  - ").append(v).append('\n');
			}
			fail(sb.toString());
		}
	}

	@Test
	public void packagePrivateHppcWrappersDoNotExtendHppc() {
		// Composition-only wrappers: IS-A HPPC would still couple the type
		// hierarchy even when package-private.
		assertTrue(!LongObjectHashMapClass.isAssignableFrom(ClearableLongObjectHashMap.class));
		assertTrue(!IntObjectHashMapClass.isAssignableFrom(ClearableIntObjectHashMap.class));
		assertTrue(!ObjectHashSetClass.isAssignableFrom(OpenAddressedHashSet.class));
	}

	@Test
	public void jdkFacadeSurfacesUseOnlyJdkCollectionTypes() throws Exception {
		// LL1Table: ConcurrentMap
		Field ll1 = ATN.class.getDeclaredField("LL1Table");
		assertTrue(Modifier.isProtected(ll1.getModifiers()));
		assertTrue(java.util.concurrent.ConcurrentMap.class.isAssignableFrom(ll1.getType()));
		assertTrue(!isHppcName(ll1.getType().getName()));

		// ATNConfigSet: Set
		assertTrue(java.util.Set.class.isAssignableFrom(ATNConfigSet.class));

		// Busy-set SPI parameter on protected closure is Set
		Method closure = null;
		for (Method m : ParserATNSimulator.class.getDeclaredMethods()) {
			if (m.getName().equals("closure") && m.getParameterTypes().length == 9) {
				closure = m;
				break;
			}
		}
		assertTrue(closure != null);
		assertTrue(Modifier.isProtected(closure.getModifiers()));
		assertTrue(java.util.Set.class.equals(closure.getParameterTypes()[3]));
	}

	// -------------------------------------------------------------------------

	/** Resolved at class-init so the test still compiles if HPPC is shaded in the main jar. */
	private static final Class<?> LongObjectHashMapClass = resolveHppc("LongObjectHashMap");
	private static final Class<?> IntObjectHashMapClass = resolveHppc("IntObjectHashMap");
	private static final Class<?> ObjectHashSetClass = resolveHppc("ObjectHashSet");

	private static Class<?> resolveHppc(String simpleName) {
		String[] candidates = {
			"com.carrotsearch.hppc." + simpleName,
			"org.antlr.v4.runtime.shaded.com.carrotsearch.hppc." + simpleName,
		};
		for (String name : candidates) {
			try {
				return Class.forName(name);
			}
			catch (ClassNotFoundException ignored) {
				// try next
			}
		}
		// If HPPC is absent entirely, use a dummy that nothing is assignable to.
		return Void.class;
	}

	private static void checkType(String where, Type type, List<String> violations) {
		if (type == null) {
			return;
		}
		if (type instanceof Class) {
			Class<?> c = (Class<?>) type;
			if (c.isArray()) {
				checkType(where, c.getComponentType(), violations);
				return;
			}
			if (isHppcName(c.getName())) {
				violations.add(where + " -> " + c.getName());
			}
			return;
		}
		if (type instanceof ParameterizedType) {
			ParameterizedType pt = (ParameterizedType) type;
			checkType(where, pt.getRawType(), violations);
			for (Type arg : pt.getActualTypeArguments()) {
				checkType(where, arg, violations);
			}
			return;
		}
		if (type instanceof TypeVariable) {
			for (Type bound : ((TypeVariable<?>) type).getBounds()) {
				checkType(where, bound, violations);
			}
			return;
		}
		if (type instanceof WildcardType) {
			for (Type bound : ((WildcardType) type).getUpperBounds()) {
				checkType(where, bound, violations);
			}
			for (Type bound : ((WildcardType) type).getLowerBounds()) {
				checkType(where, bound, violations);
			}
		}
	}

	private static boolean isHppcName(String name) {
		if (name == null) {
			return false;
		}
		for (String prefix : HPPC_PREFIXES) {
			if (name.equals(prefix) || name.startsWith(prefix + ".")) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Loads classes under {@code org.antlr.v4.runtime} from the test classpath
	 * (target/classes directories and jars).
	 */
	private static List<Class<?>> loadRuntimeClasses() throws IOException, ClassNotFoundException {
		List<Class<?>> result = new ArrayList<Class<?>>();
		ClassLoader cl = TestHppcApiBoundary.class.getClassLoader();
		Enumeration<URL> roots = cl.getResources("org/antlr/v4/runtime");
		while (roots.hasMoreElements()) {
			URL url = roots.nextElement();
			String protocol = url.getProtocol();
			if ("file".equals(protocol)) {
				File dir = new File(url.getPath());
				collectFromDirectory(dir, RUNTIME_PACKAGE_PREFIX, result);
			}
			else if ("jar".equals(protocol)) {
				String path = url.getPath();
				// jar:file:/.../foo.jar!/org/antlr/v4/runtime
				int bang = path.indexOf('!');
				String jarPath = path.substring(5, bang); // strip "file:"
				if (jarPath.startsWith("/") && jarPath.length() > 2 && jarPath.charAt(2) == ':') {
					// Windows-style /C:/...
					jarPath = jarPath.substring(1);
				}
				collectFromJar(jarPath, result);
			}
		}
		return result;
	}

	private static void collectFromDirectory(File dir, String packageName, List<Class<?>> out)
		throws ClassNotFoundException
	{
		if (dir == null || !dir.isDirectory()) {
			return;
		}
		File[] files = dir.listFiles();
		if (files == null) {
			return;
		}
		for (File f : files) {
			if (f.isDirectory()) {
				collectFromDirectory(f, packageName + "." + f.getName(), out);
			}
			else if (f.getName().endsWith(".class") && f.getName().indexOf('$') < 0) {
				// top-level only; nested types are still scanned via getDeclared*
				String simple = f.getName().substring(0, f.getName().length() - 6);
				String name = packageName + "." + simple;
				if (name.startsWith(RUNTIME_PACKAGE_PREFIX)) {
					try {
						out.add(Class.forName(name, false, TestHppcApiBoundary.class.getClassLoader()));
					}
					catch (NoClassDefFoundError ignored) {
						// optional / incomplete dependency
					}
					catch (ClassNotFoundException ignored) {
						// skip
					}
				}
			}
			else if (f.getName().endsWith(".class")) {
				// nested class
				String simple = f.getName().substring(0, f.getName().length() - 6).replace('$', '.');
				// Class.forName wants $ for nested
				String binary = packageName + "." + f.getName().substring(0, f.getName().length() - 6);
				// Wait - packageName is for outer path; nested file is Outer$Inner.class in same dir
				String outerPackage = packageName;
				String binaryName = outerPackage + "." + f.getName().substring(0, f.getName().length() - 6);
				// binary name uses $ which is already in filename
				try {
					out.add(Class.forName(binaryName, false, TestHppcApiBoundary.class.getClassLoader()));
				}
				catch (Throwable ignored) {
					// skip unloadable nested types
				}
			}
		}
	}

	private static void collectFromJar(String jarPath, List<Class<?>> out) throws IOException {
		JarFile jar = new JarFile(jarPath);
		try {
			Enumeration<JarEntry> entries = jar.entries();
			while (entries.hasMoreElements()) {
				JarEntry e = entries.nextElement();
				String name = e.getName();
				if (!name.startsWith("org/antlr/v4/runtime/") || !name.endsWith(".class")) {
					continue;
				}
				// Skip pure shaded HPPC classes themselves — we only care about
				// ANTLR API types referencing them, not that HPPC exists relocated.
				if (name.startsWith("org/antlr/v4/runtime/shaded/")) {
					continue;
				}
				String binary = name.substring(0, name.length() - 6).replace('/', '.');
				try {
					out.add(Class.forName(binary, false, TestHppcApiBoundary.class.getClassLoader()));
				}
				catch (Throwable ignored) {
					// skip
				}
			}
		}
		finally {
			jar.close();
		}
	}
}
