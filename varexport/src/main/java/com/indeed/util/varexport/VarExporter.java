// Copyright 2009 Indeed
package com.indeed.util.varexport;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.log4j.Logger;

import java.io.PrintWriter;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Enables exporting dynamic variables from classes (static fields/methods only) or objects (instance and
 * static fields/methods) that have been annotated with {@link Export}, as well as individual fields/methods that do
 * not have to be annotated.
 *
 * @author jack@indeed.com (Jack Humphrey)
 */
public class VarExporter {

    private static Logger log = Logger.getLogger(VarExporter.class);

    @VisibleForTesting
    protected static ManagedVariable<String> startTime = createStartTimeVariable(new Date());

    private static final Map<String, VarExporter> namespaces = Maps.newHashMap();

    /**
     * Load an exporter for a given namespace. For the global namespace, use {@link #global()}.
     * @param namespace Namespace to load exporter from; use null for the global namespace or call {@link #global()}
     * @return exporter for the namespace; will be created if never before accessed.
     */
    public static synchronized VarExporter forNamespace(String namespace) {
        if (Strings.isNullOrEmpty(namespace)) {
            namespace = null;
        }
        VarExporter exporter = namespaces.get(namespace);
        if (exporter == null) {
            exporter = new VarExporter(namespace);
            namespaces.put(namespace, exporter);
        }
        return exporter;
    }

    public static synchronized List<String> getNamespaces() {
        return new ArrayList<String>(namespaces.keySet());
    }

    /** @return exporter for the global namespace, use {@link #forNamespace(String)} for a specific exporter **/
    public static VarExporter global() {
        return forNamespace(null);
    }

    /** Use with visitVariables methods to visit all variables in an exporter */
    public static interface Visitor {
        void visit(Variable var);
    }

    /**
     * Visit all variables in the given namespace
     * @param namespace namespace to visit
     * @param visitor visitor to receive callbacks
     */
    public static void visitNamespaceVariables(String namespace, Visitor visitor) {
        forNamespace(namespace).visitVariables(visitor);
    }

    private final String namespace;

    private final Map<String, Variable> variables = Collections.synchronizedMap(Maps.<String, Variable>newTreeMap());

    private VarExporter parent = null;

    private VarExporter(String namespace) {
        this.namespace = namespace == null ? "" : namespace;
    }

    /**
     * Export variables subsequently exported into this namespace into the global namespace using
     * "NAMESPACE-" as a prefix.
     * @return the current namespace (not the parent)
     */
    public VarExporter includeInGlobal() {
        if (Strings.isNullOrEmpty(namespace)) {
            // already global
            return this;
        }
        return setParentNamespace(global());
    }

    /**
     * Export variables subsequently exported into this namespace into the given parent namespace using
     * "NAMESPACE-" as a prefix.
     * @param namespace parent namespace
     * @return the current namespace (not the parent)
     */
    public VarExporter setParentNamespace(VarExporter namespace) {
        if (namespace != this) {
            parent = namespace;
        }
        return this;
    }

    public String getNamespace() {
        return namespace;
    }

    public VarExporter getParentNamespace() {
        return parent;
    }

    /**
     * Export all public fields and methods of a given object instance, including static fields, that are annotated
     * with {@link com.indeed.util.varexport.Export}. Also finds annotation on interfaces.
     * @param obj object instance to export
     * @param prefix prefix for variable names (e.g. "mywidget-")
     */
    public void export(Object obj, String prefix) {
        Class c = obj.getClass();
        for (Field field : c.getFields()) {
            final ExportData export = getExportData(field);
            loadMemberVariable(field, export, obj, true, prefix, null);
        }
        Set<Class<?>> classAndInterfaces = Sets.newHashSet();
        getAllInterfaces(c, classAndInterfaces);
        classAndInterfaces.add(c);
        for (Class<?> cls : classAndInterfaces) {
            for (Method method : cls.getMethods()) {
                final ExportData export = getExportData(method);
                loadMemberVariable(method, export, obj, true, prefix, null);
            }
        }
    }

    private void getAllInterfaces(Class<?> c, Set<Class<?>> alreadySeen) {
        for (Class<?> i : c.getInterfaces()) {
            alreadySeen.add(i);
        }
        if (c.getSuperclass() != null) {
            getAllInterfaces(c.getSuperclass(), alreadySeen);
        }
    }

    /**
     * Export all public static fields and methods of a given class that are annotated with
     * {@link com.indeed.util.varexport.Export}.
     * @param c class to export
     * @param prefix prefix for variable names (e.g. "mywidget-")
     */
    public void export(Class c, String prefix) {
        for (Field field : c.getFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                ExportData export = getExportData(field);
                loadMemberVariable(field, export, c, true, prefix, null);
            }
        }
        for (Method method : c.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())) {
                ExportData export = getExportData(method);
                loadMemberVariable(method, export, c, true, prefix, null);
            }
        }
    }

    /**
     * Export a given public {@link Field} or {@link Method} of a given object instance. The member will be exported
     * even if it does not have the {@link Export} annotation. This is mainly useful for exporting variables for which
     * you cannot modify the code to add an annotation.
     * @param obj object instance
     * @param member Field of Method to export
     * @param prefix prefix for variable names (e.g. "mywidget-")
     * @param name Name to use for export (optional, will be ignored if Export annotation used)
     */
    public void export(Object obj, Member member, String prefix, String name) {
        ExportData export = null;
        if (member instanceof AnnotatedElement) {
            export = getExportData((AnnotatedElement) member);
        }
        loadMemberVariable(member, export, obj, false, prefix, name);
    }

    /**
     * Export a given public static {@link Field} or {@link Method} of a given class. The member will be exported
     * even if it does not have the {@link Export} annotation. This is mainly useful for exporting variables for which
     * you cannot modify the code to add an annotation.
     * @param c class from which to export
     * @param member Field of Method to export
     * @param prefix prefix for variable names (e.g. "mywidget-")
     * @param name Name to use for export (optional, will be ignored if Export annotation used)
     */
    public void export(Class c, Member member, String prefix, String name) {
        if (!Modifier.isStatic(member.getModifiers())) {
            throw new UnsupportedOperationException(member + " is not static in " + c.getName());
        }
        export((Object) c, member, prefix, name);
    }

    public void export(LazilyManagedVariable lazilyManagedVariable) {
        addVariable(lazilyManagedVariable);
    }

    /**
     * Export a manually-managed variable.
     * @param managedVariable manually-managed variable
     */
    public void export(ManagedVariable managedVariable) {
        addVariable(managedVariable);
    }

    /**
     * Load the current value of a given variable
     * @param variableName name of variable
     * @return value
     */
    @SuppressWarnings("unchecked")
    public <T> T getValue(String variableName) {
        Variable variable = getVariable(variableName);
        return variable == null ? null : (T) variable.getValue();
    }

    /**
     * Load the dynamic variable object
     * @param variableName name of variable
     * @return variable
     */
    @SuppressWarnings("unchecked")
    public <T> Variable<T> getVariable(String variableName) {
        String[] subTokens = getSubVariableTokens(variableName);
        if (subTokens != null) {
            Variable<T> sub = getSubVariable(subTokens[0], subTokens[1]);
            if (sub != null) {
                return sub;
            }
        }
        return variables.get(variableName);
    }

    /**
     * Visit all the values exported by this exporter.
     * @param visitor visitor to receive visit callbacks
     */
    public void visitVariables(Visitor visitor) {
        // protects against ConcurrentModificationException because .toArray() is synchronized
        final Variable[] variablesCopy = variables.values().toArray(new Variable[variables.size()]);

        for (Variable v : variablesCopy) {
            if (v.isExpandable()) {
                Map<?, ?> map = v.expand();
                try {
                    for (final Map.Entry entry : map.entrySet()) {
                        visitor.visit(new EntryVariable(entry, v));
                    }
                } catch (ConcurrentModificationException e) {
                    log.warn("Failed to iterate map entry set for variable " + v.getName(), e);
                    Map.Entry<String, String> errorEntry = new AbstractMap.SimpleEntry<String, String>("error", e.getMessage());
                    visitor.visit(new EntryVariable(errorEntry, v));
                }
            } else {
                visitor.visit(v);
            }
        }
        if (variablesCopy.length > 0 && startTime != null) {
            visitor.visit(startTime);
        }
    }

    /** @return an iterator over the exported variables */
    public Iterable<Variable> getVariables() {
        final ImmutableList.Builder<Variable> builder = ImmutableList.builder();
        visitVariables(new Visitor() {
            public void visit(Variable var) {
                builder.add(var);
            }
        });
        return builder.build();
    }

    /**
     * Write all variables, one per line, to the given writer, in the format "name=value".
     * Will escape values for compatibility with loading into {@link java.util.Properties}.
     * @param out writer
     * @param includeDoc true if documentation comments should be included
     */
    public void dump(final PrintWriter out, final boolean includeDoc) {
        visitVariables(new Visitor() {
            public void visit(Variable var) {
                var.write(out, includeDoc);
            }
        });
    }

    /**
     * Write all variables as a JSON object. Will not escape names or values. All values are
     * written as Strings.
     * @param out writer
     */
    public void dumpJson(final PrintWriter out) {
        out.append("{");
        visitVariables(new Visitor() {
            int count = 0;

            public void visit(Variable var) {
                if (count++ > 0) { out.append(", "); }
                out.append(var.getName()).append("='").append(String.valueOf(var.getValue())).append("'");
            }
        });
        out.append("}");
    }

    /** Remove all exported variables. Does not remove variables exported to a parent namespace */
    public void reset() {
        variables.clear();
    }

    private String[] getSubVariableTokens(String variableName) {
        String[] tokens = variableName.split("#", 2);
        if (tokens.length > 1) {
            return tokens;
        }
        return null;
    }

    private Variable getSubVariable(String variableName, String subVariableName) {
        Variable container = variables.get(variableName);
        if (container != null && container.isExpandable()) {
            Map<?, ?> map = container.expand();
            try {
                for (Map.Entry entry : map.entrySet()) {
                    Object key = entry.getKey();
                    if (String.valueOf(key).equals(subVariableName)) {
                        return new EntryVariable(entry, container);
                    }
                }
            } catch (ConcurrentModificationException e) {
                log.warn("Failed to iterate map entry set for variable " + variableName, e);
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void addVariable(Variable variable) {
        Variable prev = variables.put(variable.getName(), variable);
        if (prev != null) {
            log.warn("In namespace '" + namespace + "': Exporting variable named " + variable.getName() +
                    " hides a previously exported variable");
        } else {
            if (log.isDebugEnabled()) {
                log.debug("In namespace '" + namespace + "': Added variable " + variable.getName());
            }
        }
        if (parent != null && parent != this) {
            parent.addVariable(new ProxyVariable(namespace + "-" + variable.getName(), variable));
        }
    }

    @SuppressWarnings("unchecked")
    private void loadMemberVariable(Member member, ExportData export, Object obj, boolean requireAnnotation, String prefix, String name) {
        if (!requireAnnotation || export != null) {
            Variable variable = variableFromMember(export, prefix, name, member, obj);
            if (export != null && export.cacheTimeoutMs() > 0) {
                variable = new CachingVariable(variable, export.cacheTimeoutMs());
            }
            addVariable(variable);
        }
    }

    private String getVarName(ExportData export, String supplied, Member member) {
        if (export != null && export.name() != null && export.name().length() > 0) {
            return export.name();
        } else if (supplied != null && supplied.length() > 0) {
            return supplied;
        } else {
            return member.getName();
        }
    }

    private Variable variableFromMember(ExportData export, String prefix, String suppliedName, Member member, Object obj) {
        final String name = (prefix != null ? prefix : "") + getVarName(export, suppliedName, member);
        final String doc = export != null ? export.doc() : "";
        final boolean expand = export != null ? export.expand() : false;
        if (member instanceof Field) {
            return new FieldVariable(name, doc, expand, (Field) member, obj);
        } else if (member instanceof Method) {
            return new MethodVariable(name, doc, expand, (Method) member, obj);
        } else {
            throw new UnsupportedOperationException(member.getClass() + " not supported by export");
        }
    }

    @VisibleForTesting
    protected static ManagedVariable<String> createStartTimeVariable(final Date date) {
        final SimpleDateFormat timestampFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssz"); // ISO 8601
        return ManagedVariable.<String>builder()
            .setName("exporter-start-time")
            .setDoc("global start time of variable exporter")
            .setValue(timestampFormat.format(date))
            .build();
    }

    private static ExportData getExportData(final AnnotatedElement element) {
        ExportData data = null;
        final Export export = element.getAnnotation(Export.class);
        if (export != null) {
            return new ExplicitAnnotationExportData(export);
        } else {
            for (Annotation annotation : element.getAnnotations()) {
                data = LegacyAnnotationExportData.fromLegacy(element, annotation);
                if (data != null) {
                    break;
                }
            }
        }
        return data;
    }

    private static class FieldVariable<T> extends Variable<T> {
        private Field field;
        private Object object;

        public FieldVariable(String name, String doc, boolean expand, Field field, Object object) {
            super(name, doc, expand);
            this.field = field;
            this.object = object;
            if (Map.class.isAssignableFrom(field.getType()) && !ImmutableMap.class.isAssignableFrom(field.getType())) {
                log.warn("Variable " + name + " is not an ImmutableMap, which may result in sporadic errors");
            }
        }

        protected boolean canExpand() {
            return Map.class.isAssignableFrom(field.getType()) && getValue() != null;
        }

        @SuppressWarnings("unchecked")
        public T getValue() {
            try {
                return (T) field.get(object);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static class MethodVariable<T> extends Variable<T> {
        private Method method;
        private Object object;

        public MethodVariable(String name, String doc, boolean expand, Method method, Object object) {
            super(name, doc, expand);
            this.method = method;
            this.object = object;
            if (Map.class.isAssignableFrom(method.getReturnType()) && !ImmutableMap.class.isAssignableFrom(method.getReturnType())) {
                log.warn("Variable " + name + " is not an ImmutableMap, which may result in sporadic errors");
            }
        }

        protected boolean canExpand() {
            return Map.class.isAssignableFrom(method.getReturnType()) && getValue() != null;
        }

        @SuppressWarnings("unchecked")
        public T getValue() {
            try {
                return (T) method.invoke(object);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            } catch (InvocationTargetException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static class EntryVariable extends Variable {
        private Object value;
        private Variable parent;

        public EntryVariable(Map.Entry entry, Variable parent) {
            super(parent.getName() + "#" + entry.getKey(), null, false);
            this.value = entry.getValue();
            this.parent = parent;
            if (value != null &&
                    Map.class.isAssignableFrom(value.getClass()) &&
                    !ImmutableMap.class.isAssignableFrom(value.getClass())) {
                log.warn("Variable " + parent.getName() + "#" + entry.getKey() +
                        " is not an ImmutableMap, which may result in sporadic errors");
            }
        }

        protected boolean canExpand() {
            return Map.class.isAssignableFrom(value.getClass()) && getValue() != null;
        }

        @Override
        public String getDoc() {
            return parent.getDoc();
        }

        @Override
        public Long getLastUpdated() {
            return parent.getLastUpdated();
        }

        public Object getValue() {
            return value;
        }
    }

    protected static class CachingVariable<T> extends ProxyVariable<T> {

        private final long timeout;

        private T cachedValue = null;
        private long lastCached = 0;

        private Supplier<Long> clock = new Supplier<Long>() {
            public Long get() {
                return System.currentTimeMillis();
            }
        };

        public CachingVariable(Variable<T> variable, long timeout) {
            super(variable.getName(), variable);
            this.timeout = timeout;
        }

        @VisibleForTesting
        protected void setClock(Supplier<Long> clock) {
            this.clock = clock;
        }

        private boolean isCacheExpired() {
            return lastCached == 0 || clock.get() - lastCached > timeout;
        }

        @Override
        public Long getLastUpdated() {
            return lastCached;
        }

        @Override
        public T getValue() {
            if (isCacheExpired()) {
                cachedValue = super.getValue();
                lastCached = clock.get();
            }
            return cachedValue;
        }
    }

    protected static class ProxyVariable<T> extends Variable<T> {
        private final Variable<T> variable;

        public ProxyVariable(String name, Variable<T> variable) {
            super(name, variable.getDoc(), variable.isExpandable());
            this.variable = variable;
        }

        @Override
        protected boolean canExpand() {
            return variable.canExpand();
        }

        public T getValue() {
            return variable.getValue();
        }

        @Override
        public Long getLastUpdated() {
            return variable.getLastUpdated();
        }
    }
}
