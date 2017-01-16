/*
 * Copyright 2015 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.grails.datastore.mapping.reflect

import groovy.transform.CompileStatic
import groovy.transform.Memoized
import groovy.transform.TypeChecked
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.GenericsType
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.classgen.VariableScopeVisitor
import org.codehaus.groovy.control.Janitor
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.runtime.MetaClassHelper
import org.codehaus.groovy.syntax.Token
import org.codehaus.groovy.syntax.Types
import org.springframework.util.StringUtils

import javax.persistence.Entity
import java.lang.annotation.Annotation
import java.lang.reflect.Modifier
import java.util.regex.Pattern

import static org.codehaus.groovy.ast.tools.GenericsUtils.correctToGenericsSpecRecurse


/**
 * Utility methods for dealing with Groovy ASTs
 *
 * @author Graeme Rocher
 * @since 5.0
 */
@CompileStatic
class AstUtils {
    private static final String SPEC_CLASS = "spock.lang.Specification";
    private static final Set<String> JUNIT_ANNOTATION_NAMES = new HashSet<String>(Arrays.asList("org.junit.Before", "org.junit.After"));
    private static final Class<?>[] EMPTY_JAVA_CLASS_ARRAY = [];
    private static final Class<?>[] OBJECT_CLASS_ARG = [Object.class];

    public static final ClassNode COMPILE_STATIC_TYPE = ClassHelper.make(CompileStatic)
    public static final ClassNode TYPE_CHECKED_TYPE = ClassHelper.make(TypeChecked)
    public static final Object TRANSFORM_APPLIED_MARKER = new Object();
    public static final String DOMAIN_TYPE = "Domain"
    public static final Parameter[] ZERO_PARAMETERS = new Parameter[0];
    public static final ClassNode[] EMPTY_CLASS_ARRAY = new ClassNode[0];
    public static final Token ASSIGNMENT_OPERATOR = Token.newSymbol(Types.ASSIGNMENT_OPERATOR, 0, 0);
    public static final ArgumentListExpression ZERO_ARGUMENTS = new ArgumentListExpression();
    public static final ClassNode OBJECT_CLASS_NODE = new ClassNode(Object.class).getPlainNodeReference();

    private static final Set<String> TRANSFORMED_CLASSES = new HashSet<String>();
    private static final Set<String> ENTITY_ANNOTATIONS = ["grails.persistence.Entity", "grails.gorm.annotation.Entity", Entity.class.getName()] as Set<String>

    /**
     * @return The names of the transformed entities for this context
     */
    public static Collection<String> getKnownEntityNames() {
        return Collections.unmodifiableCollection( TRANSFORMED_CLASSES );
    }

    /**
     * @param name Adds the name of a transformed entity
     */
    public static void addTransformedEntityName(String name) {
        TRANSFORMED_CLASSES.add(name)
    }
    /**
     * The name of the Grails application directory
     */

    public static final String GRAILS_APP_DIR = "grails-app";

    public static final String REGEX_FILE_SEPARATOR = "[\\\\/]"; // backslashes need escaping in regexes
    /*
     Domain path is always matched against the normalized File representation of an URL and
     can therefore work with slashes as separators.
     */
    public static Pattern DOMAIN_PATH_PATTERN = Pattern.compile(".+" + REGEX_FILE_SEPARATOR + GRAILS_APP_DIR + REGEX_FILE_SEPARATOR + "domain" + REGEX_FILE_SEPARATOR + "(.+)\\.(groovy|java)");

    private static final Map<String, ClassNode> emptyGenericsPlaceHoldersMap = Collections.emptyMap();

    /**
     * Checks whether the file referenced by the given url is a domain class
     *
     * @param url The URL instance
     * @return true if it is a domain class
     */
    public static boolean isDomainClass(URL url) {
        if (url == null) return false;

        return DOMAIN_PATH_PATTERN.matcher(url.getFile()).find();
    }

    /**
     * Build static direct call to getter of a property
     *
     * @param objectExpression
     * @param propertyName
     * @param targetClassNode
     * @return The method call expression
     */
    public static MethodCallExpression buildGetPropertyExpression(final Expression objectExpression, final String propertyName, final ClassNode targetClassNode) {
        return buildGetPropertyExpression(objectExpression, propertyName, targetClassNode, false);
    }

    /**
     * Build static direct call to getter of a property
     *
     * @param objectExpression
     * @param propertyName
     * @param targetClassNode
     * @param useBooleanGetter
     * @return The method call expression
     */
    public static MethodCallExpression buildGetPropertyExpression(final Expression objectExpression, final String propertyName, final ClassNode targetClassNode, final boolean useBooleanGetter) {
        String methodName = (useBooleanGetter ? "is" : "get") + MetaClassHelper.capitalize(propertyName);
        MethodCallExpression methodCallExpression = new MethodCallExpression(objectExpression, methodName, MethodCallExpression.NO_ARGUMENTS);
        MethodNode getterMethod = targetClassNode.getGetterMethod(methodName);
        if(getterMethod != null) {
            methodCallExpression.setMethodTarget(getterMethod);
        }
        return methodCallExpression;
    }

    /**
     * Build static direct call to setter of a property
     *
     * @param objectExpression
     * @param propertyName
     * @param targetClassNode
     * @param valueExpression
     * @return The method call expression
     */
    public static MethodCallExpression buildSetPropertyExpression(final Expression objectExpression, final String propertyName, final ClassNode targetClassNode, final Expression valueExpression) {
        String methodName = "set" + MetaClassHelper.capitalize(propertyName);
        MethodCallExpression methodCallExpression = new MethodCallExpression(objectExpression, methodName, new ArgumentListExpression(valueExpression));
        MethodNode setterMethod = targetClassNode.getSetterMethod(methodName);
        if(setterMethod != null) {
            methodCallExpression.setMethodTarget(setterMethod);
        }
        return methodCallExpression;
    }

    public static void processVariableScopes(SourceUnit source, ClassNode classNode, MethodNode methodNode) {
        VariableScopeVisitor scopeVisitor = new VariableScopeVisitor(source)
        if(methodNode == null) {
            scopeVisitor.visitClass(classNode)
        } else {
            scopeVisitor.prepareVisit(classNode)
            scopeVisitor.visitMethod(methodNode)
        }
    }

    /**
     * Returns true if MethodNode is marked with annotationClass
     * @param methodNode A MethodNode to inspect
     * @param annotationClass an annotation to look for
     * @return true if classNode is marked with annotationClass, otherwise false
     */
    public static boolean hasAnnotation(final MethodNode methodNode, final Class<? extends Annotation> annotationClass) {
        return !methodNode.getAnnotations(new ClassNode(annotationClass)).isEmpty();
    }

    /**
     * Whether the class is a Spock test
     *
     * @param classNode The class node
     * @return True if it is
     */
    public static boolean isSpockTest(ClassNode classNode) {
        return isSubclassOf(classNode, SPEC_CLASS);
    }

    /**
     * Whether the method node has any JUnit annotations
     *
     * @param md The method node
     * @return True if it does
     */
    public static boolean hasJunitAnnotation(MethodNode md) {
        for (AnnotationNode annotation in md.getAnnotations()) {
            if(JUNIT_ANNOTATION_NAMES.contains(annotation.getClassNode().getName())) {
                return true
            }
        }
        return false
    }


    /**
     * Returns true if MethodNode is marked with annotationClass
     * @param methodNode A MethodNode to inspect
     * @param annotationClass an annotation to look for
     * @return true if classNode is marked with annotationClass, otherwise false
     */
    public static boolean hasAnnotation(final MethodNode methodNode, String annotationClassName) {
        List<AnnotationNode> annos = methodNode.getAnnotations()
        for(ann in annos) {
            if(ann.classNode.name == annotationClassName) return true
        }
        return false
    }

    public static boolean hasAnnotation(List<AnnotationNode> annotationNodes, AnnotationNode annotationNode) {
        return annotationNodes.any() { AnnotationNode ann ->
            ann.classNode.equals(annotationNode.classNode)
        }
    }


    /**
     * @param classNode a ClassNode to search
     * @param annotationsToLookFor Annotations to look for
     * @return true if classNode is marked with any of the annotations in annotationsToLookFor
     */
    public static boolean hasAnyAnnotations(final ClassNode classNode, final Class<? extends Annotation>... annotationsToLookFor) {
        for (Class<? extends Annotation> annotationClass : annotationsToLookFor) {
            if(hasAnnotation(classNode, annotationClass)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if classNode is marked with annotationClass
     * @param classNode A ClassNode to inspect
     * @param annotationClass an annotation to look for
     * @return true if classNode is marked with annotationClass, otherwise false
     */
    public static boolean hasAnnotation(final ClassNode classNode, final Class<? extends Annotation> annotationClass) {
        return !classNode.getAnnotations(new ClassNode(annotationClass)).isEmpty();
    }

    public static Parameter[] copyParameters(Parameter[] parameterTypes) {
        return copyParameters(parameterTypes, null);
    }

    public static Parameter[] copyParameters(Parameter[] parameterTypes, Map<String, ClassNode> genericsPlaceholders) {
        Parameter[] newParameterTypes = new Parameter[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            Parameter parameterType = parameterTypes[i];
            Parameter newParameter = new Parameter(replaceGenericsPlaceholders(parameterType.getType(), genericsPlaceholders), parameterType.getName(), parameterType.getInitialExpression());
            copyAnnotations(parameterType, newParameter);
            newParameterTypes[i] = newParameter;
        }
        return newParameterTypes;
    }

    public static Parameter[] copyParameters(Map<String, ClassNode> genericsSpec, Parameter[] parameterTypes, List<String> currentMethodGenPlaceholders) {
        Parameter[] newParameterTypes = new Parameter[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            Parameter parameterType = parameterTypes[i];
            ClassNode newParamType = correctToGenericsSpecRecurse(genericsSpec, parameterType.getType(), currentMethodGenPlaceholders);
            Parameter newParameter = new Parameter(newParamType, parameterType.getName(), parameterType.getInitialExpression());
            newParameter.addAnnotations(parameterType.getAnnotations());
            newParameterTypes[i] = newParameter;
        }
        return newParameterTypes;
    }
    public static void copyAnnotations(final AnnotatedNode from, final AnnotatedNode to) {
        copyAnnotations(from, to, null, null);
    }

    public static void copyAnnotations(final AnnotatedNode from, final AnnotatedNode to, final Set<String> included, final Set<String> excluded) {
        final List<AnnotationNode> annotationsToCopy = from.getAnnotations();
        for(final AnnotationNode node : annotationsToCopy) {
            String annotationClassName = node.getClassNode().getName();
            if((excluded==null || !excluded.contains(annotationClassName)) &&
                    (included==null || included.contains(annotationClassName))) {
                final AnnotationNode copyOfAnnotationNode = cloneAnnotation(node);
                to.addAnnotation(copyOfAnnotationNode);
            }
        }
    }

    public static AnnotationNode cloneAnnotation(final AnnotationNode node) {
        final AnnotationNode copyOfAnnotationNode = new AnnotationNode(node.getClassNode());
        final Map<String, Expression> members = node.getMembers();
        for(final Map.Entry<String, Expression> entry : members.entrySet()) {
            copyOfAnnotationNode.addMember(entry.getKey(), entry.getValue());
        }
        return copyOfAnnotationNode;
    }

    public static boolean isEnum(ClassNode classNode) {
        ClassNode parent = classNode.getSuperClass();
        while (parent != null) {
            if (parent.getName().equals("java.lang.Enum"))
                return true;
            parent = parent.getSuperClass();
        }
        return false;
    }

    /**
     * Obtains a property from the class hierarchy
     *
     * @param cn The class node
     * @param name The property name
     * @return The property node or null
     */
    static PropertyNode getPropertyFromHierarchy(ClassNode cn, String name) {
        PropertyNode pn = cn.getProperty(name)
        ClassNode superClass = cn.getSuperClass()
        while(pn == null && superClass != null) {
            pn = superClass.getProperty(name)
            if(pn != null) return pn
            superClass = superClass.getSuperClass()
        }
        return pn
    }
    @Memoized
    public static boolean isDomainClass(ClassNode classNode) {
        if (classNode == null) return false;
        if(implementsInterface(classNode, "org.grails.datastore.gorm.GormEntity")) {
            return true
        }
        String filePath = classNode.getModule() != null ? classNode.getModule().getDescription() : null;
        if (filePath != null) {
            try {
                if (isDomainClass(new File(filePath).toURI().toURL())) {
                    return true;
                }
            } catch (MalformedURLException e) {
                // ignore
            }
        }
        List<AnnotationNode> annotations = classNode.getAnnotations();
        if (annotations != null && !annotations.isEmpty()) {
            for (AnnotationNode annotation : annotations) {
                String className = annotation.getClassNode().getName();
                if( ENTITY_ANNOTATIONS.any() { String ann -> ann.equals(className)} ) {
                    return true
                }
            }
        }
        return false;
    }

    public static ClassNode nonGeneric(ClassNode type) {
        return replaceGenericsPlaceholders(type, emptyGenericsPlaceHoldersMap);
    }

    @SuppressWarnings("unchecked")
    public static ClassNode nonGeneric(ClassNode type, final ClassNode wildcardReplacement) {
        return replaceGenericsPlaceholders(type, emptyGenericsPlaceHoldersMap, wildcardReplacement);
    }

    public static ClassNode replaceGenericsPlaceholders(ClassNode type, Map<String, ClassNode> genericsPlaceholders) {
        return replaceGenericsPlaceholders(type, genericsPlaceholders, null);
    }

    public static ClassNode replaceGenericsPlaceholders(ClassNode type, Map<String, ClassNode> genericsPlaceholders, ClassNode defaultPlaceholder) {
        if (type.isArray()) {
            return replaceGenericsPlaceholders(type.getComponentType(), genericsPlaceholders).makeArray();
        }

        if (!type.isUsingGenerics() && !type.isRedirectNode()) {
            return type.getPlainNodeReference();
        }

        if(type.isGenericsPlaceHolder() && genericsPlaceholders != null) {
            final ClassNode placeHolderType;
            if(genericsPlaceholders.containsKey(type.getUnresolvedName())) {
                placeHolderType = genericsPlaceholders.get(type.getUnresolvedName());
            } else {
                placeHolderType = defaultPlaceholder;
            }
            if(placeHolderType != null) {
                return placeHolderType.getPlainNodeReference();
            } else {
                return ClassHelper.make(Object.class).getPlainNodeReference();
            }
        }

        final ClassNode nonGen = type.getPlainNodeReference();

        if("java.lang.Object".equals(type.getName())) {
            nonGen.setGenericsPlaceHolder(false);
            nonGen.setGenericsTypes(null);
            nonGen.setUsingGenerics(false);
        } else {
            if(type.isUsingGenerics()) {
                GenericsType[] parameterized = type.getGenericsTypes();
                if (parameterized != null && parameterized.length > 0) {
                    GenericsType[] copiedGenericsTypes = new GenericsType[parameterized.length];
                    for (int i = 0; i < parameterized.length; i++) {
                        GenericsType parameterizedType = parameterized[i];
                        GenericsType copiedGenericsType = null;
                        if (parameterizedType.isPlaceholder() && genericsPlaceholders != null) {
                            ClassNode placeHolderType = genericsPlaceholders.get(parameterizedType.getName());
                            if(placeHolderType != null) {
                                copiedGenericsType = new GenericsType(placeHolderType.getPlainNodeReference());
                            } else {
                                copiedGenericsType = new GenericsType(ClassHelper.make(Object.class).getPlainNodeReference());
                            }
                        } else {
                            copiedGenericsType = new GenericsType(replaceGenericsPlaceholders(parameterizedType.getType(), genericsPlaceholders));
                        }
                        copiedGenericsTypes[i] = copiedGenericsType;
                    }
                    nonGen.setGenericsTypes(copiedGenericsTypes);
                }
            }
        }

        return nonGen;
    }

    public static void injectTrait(ClassNode classNode, Class traitClass) {
        ClassNode traitClassNode = ClassHelper.make(traitClass);
        boolean implementsTrait = false;
        boolean traitNotLoaded = false;
        try {
            implementsTrait = classNode.declaresInterface(traitClassNode);
        } catch (Throwable e) {
            // if we reach this point, the trait injector could not be loaded due to missing dependencies (for example missing servlet-api). This is ok, as we want to be able to compile against non-servlet environments.
            traitNotLoaded = true;
        }
        if (!implementsTrait && !traitNotLoaded) {
            final GenericsType[] genericsTypes = traitClassNode.getGenericsTypes();
            final Map<String, ClassNode> parameterNameToParameterValue = new LinkedHashMap<String, ClassNode>();
            if(genericsTypes != null) {
                for(GenericsType gt : genericsTypes) {
                    parameterNameToParameterValue.put(gt.getName(), classNode);
                }
            }
            classNode.addInterface(replaceGenericsPlaceholders(traitClassNode, parameterNameToParameterValue, classNode));
        }
    }

    public static boolean hasOrInheritsProperty(ClassNode classNode, String propertyName) {
        if (hasProperty(classNode, propertyName)) {
            return true;
        }

        ClassNode parent = classNode.getSuperClass();
        while (parent != null && !parent.name.equals("java.lang.Object")) {
            if (hasProperty(parent, propertyName)) {
                return true;
            }
            parent = parent.getSuperClass();
        }

        return false;
    }

    /**
     * Returns whether a classNode has the specified property or not
     *
     * @param classNode    The ClassNode
     * @param propertyName The name of the property
     * @return true if the property exists in the ClassNode
     */
    public static boolean hasProperty(ClassNode classNode, String propertyName) {
        if (classNode == null || !StringUtils.hasText(propertyName)) {
            return false
        }

        final MethodNode method = classNode.getMethod(NameUtils.getGetterName(propertyName), Parameter.EMPTY_ARRAY)
        if (method != null) return true

        // check read-only field with setter
        if( classNode.getField(propertyName) != null && !classNode.getMethods(NameUtils.getSetterName(propertyName)).isEmpty()) {
            return true
        }

        for (PropertyNode pn in classNode.getProperties()) {
            if (pn.name.equals(propertyName) && !pn.isPrivate()) {
                return true
            }
        }

        return false
    }

    public static ClassNode getFurthestUnresolvedParent(ClassNode classNode) {
        ClassNode parent = classNode.getSuperClass()

        while (parent != null && !parent.name.equals("java.lang.Object") &&
                !parent.isResolved() && !Modifier.isAbstract(parent.getModifiers())) {
            classNode = parent
            parent = parent.getSuperClass()
        }
        return classNode;
    }

    /**
     * Adds an annotation to the give nclass node if it doesn't already exist
     *
     * @param classNode The class node
     * @param annotationClass The annotation class
     */
    public static void addAnnotationIfNecessary(AnnotatedNode classNode, Class<? extends Annotation> annotationClass) {
        addAnnotationOrGetExisting(classNode, annotationClass);
    }

    /**
     * Adds an annotation to the given class node or returns the existing annotation
     *
     * @param classNode The class node
     * @param annotationClass The annotation class
     */
    public static AnnotationNode addAnnotationOrGetExisting(AnnotatedNode classNode, Class<? extends Annotation> annotationClass) {
        return addAnnotationOrGetExisting(classNode, annotationClass, Collections.<String, Object>emptyMap());
    }

    /**
     * @return A new this variable
     */
    public static VariableExpression varThis() {
        return new VariableExpression("this")
    }

    /**
     * Adds an annotation to the given class node or returns the existing annotation
     *
     * @param annotatedNode The class node
     * @param annotationClass The annotation class
     */
    public static AnnotationNode addAnnotationOrGetExisting(AnnotatedNode annotatedNode, Class<? extends Annotation> annotationClass, Map<String, Object> members) {
        ClassNode annotationClassNode = ClassHelper.make(annotationClass);
        return addAnnotationOrGetExisting(annotatedNode, annotationClassNode, members);
    }

    public static AnnotationNode addAnnotationOrGetExisting(AnnotatedNode annotatedNode, ClassNode annotationClassNode) {
        return addAnnotationOrGetExisting(annotatedNode, annotationClassNode, Collections.<String, Object>emptyMap());
    }

    public static AnnotationNode addAnnotationOrGetExisting(AnnotatedNode annotatedNode, ClassNode annotationClassNode, Map<String, Object> members) {
        List<AnnotationNode> annotations = annotatedNode.getAnnotations();
        AnnotationNode annotationToAdd = new AnnotationNode(annotationClassNode);
        if (annotations.isEmpty()) {
            annotatedNode.addAnnotation(annotationToAdd);
        }
        else {
            AnnotationNode existing = findAnnotation(annotationClassNode, annotations);
            if (existing != null){
                annotationToAdd = existing;
            }
            else {
                annotatedNode.addAnnotation(annotationToAdd);
            }
        }

        if(members != null && !members.isEmpty()) {
            for (Map.Entry<String, Object> memberEntry : members.entrySet()) {
                Object value = memberEntry.getValue();
                annotationToAdd.setMember( memberEntry.getKey(), value instanceof Expression ? (Expression)value : new ConstantExpression(value));
            }
        }
        return annotationToAdd;
    }

    public static AnnotationNode findAnnotation(AnnotatedNode classNode, Class<?> type) {
        List<AnnotationNode> annotations = classNode.getAnnotations();
        return annotations == null ? null : findAnnotation(new ClassNode(type),annotations);
    }

    public static AnnotationNode findAnnotation(AnnotatedNode annotationClassNode, List<AnnotationNode> annotations) {
        for (AnnotationNode annotation : annotations) {
            if (annotation.getClassNode().equals(annotationClassNode)) {
                return annotation;
            }
        }
        return null;
    }

    /**
     * Tests whether the ClasNode implements the specified method name.
     *
     * @param classNode  The ClassNode
     * @param methodName The method name
     * @return true if it does implement the method
     */
    public static boolean implementsZeroArgMethod(ClassNode classNode, String methodName) {
        MethodNode method = classNode.getDeclaredMethod(methodName, Parameter.EMPTY_ARRAY);
        return method != null && (method.isPublic() || method.isProtected()) && !method.isAbstract();
    }

    @SuppressWarnings("rawtypes")
    public static boolean implementsOrInheritsZeroArgMethod(ClassNode classNode, String methodName) {
        if (implementsZeroArgMethod(classNode, methodName)) {
            return true;
        }

        ClassNode parent = classNode.getSuperClass();
        while (parent != null && !parent.name.equals("java.lang.Object")) {
            if (implementsZeroArgMethod(parent, methodName)) {
                return true;
            }
            parent = parent.getSuperClass();
        }
        return false;
    }



    public static boolean isSubclassOfOrImplementsInterface(ClassNode childClass, ClassNode superClass) {
        String superClassName = superClass.getName();
        return isSubclassOfOrImplementsInterface(childClass, superClassName);
    }

    public static boolean isSubclassOfOrImplementsInterface(ClassNode childClass, String superClassName) {
        return isSubclassOf(childClass, superClassName) || implementsInterface(childClass, superClassName);
    }

    /**
     * Returns true if the given class name is a parent class of the given class
     *
     * @param classNode The class node
     * @param parentClassName the parent class name
     * @return True if it is a subclass
     */
    public static boolean isSubclassOf(ClassNode classNode, String parentClassName) {
        ClassNode currentSuper = classNode.getSuperClass();
        while (currentSuper != null && !currentSuper.getName().equals(OBJECT_CLASS_NODE.getName())) {
            if (currentSuper.getName().equals(parentClassName)) return true;
            currentSuper = currentSuper.getSuperClass();
        }
        return false;
    }

    public static boolean implementsInterface(ClassNode classNode, String interfaceName) {
        return findInterface(classNode, interfaceName) != null
    }

    /**
     * Whether the given type is a Groovy object
     * @param type The type
     * @return True if it is
     */
    static boolean isGroovyType(ClassNode type) {
        return type.isPrimaryClassNode() || implementsInterface(type, "groovy.lang.GroovyObject")
    }

    public static ClassNode findInterface(ClassNode classNode, String interfaceName) {
        ClassNode currentClassNode = classNode;
        while (currentClassNode != null && !currentClassNode.getName().equals(OBJECT_CLASS_NODE.getName())) {
            ClassNode[] interfaces = currentClassNode.getInterfaces();

            def interfaceNode = implementsInterfaceInternal(interfaces, interfaceName)
            if (interfaceNode != null) {
                return interfaceNode
            }
            currentClassNode = currentClassNode.getSuperClass();
        }
        return null;
    }

    private static ClassNode implementsInterfaceInternal(ClassNode[] interfaces, String interfaceName) {
        for (ClassNode anInterface : interfaces) {
            if(anInterface.getName().equals(interfaceName)) {
                return anInterface;
            }
            ClassNode[] childInterfaces = anInterface.getInterfaces();
            if(childInterfaces != null && childInterfaces.length>0) {
                return implementsInterfaceInternal(childInterfaces,interfaceName );
            }

        }
        return null;
    }

    public static void warning(final SourceUnit sourceUnit, final ASTNode node, final String warningMessage) {
        final String sample = sourceUnit.getSample(node.getLineNumber(), node.getColumnNumber(), new Janitor());
        System.err.println("WARNING: " + warningMessage + "\n\n" + sample);
    }

    public static boolean isSetter(MethodNode declaredMethod) {
        return declaredMethod.getParameters().length == 1 && ReflectionUtils.isSetter(declaredMethod.getName(), OBJECT_CLASS_ARG);
    }

    public static boolean isGetter(MethodNode declaredMethod) {
        return declaredMethod.getParameters().length == 0 && ReflectionUtils.isGetter(declaredMethod.getName(), EMPTY_JAVA_CLASS_ARRAY);
    }

}
