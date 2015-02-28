package net.bytebuddy.instrumentation;

import net.bytebuddy.dynamic.TargetType;
import net.bytebuddy.instrumentation.field.FieldDescription;
import net.bytebuddy.instrumentation.field.FieldList;
import net.bytebuddy.instrumentation.method.MethodDescription;
import net.bytebuddy.instrumentation.method.bytecode.ByteCodeAppender;
import net.bytebuddy.instrumentation.method.bytecode.stack.StackManipulation;
import net.bytebuddy.instrumentation.method.bytecode.stack.assign.Assigner;
import net.bytebuddy.instrumentation.method.bytecode.stack.assign.primitive.PrimitiveTypeAwareAssigner;
import net.bytebuddy.instrumentation.method.bytecode.stack.assign.reference.ReferenceTypeAwareAssigner;
import net.bytebuddy.instrumentation.method.bytecode.stack.member.FieldAccess;
import net.bytebuddy.instrumentation.method.bytecode.stack.member.MethodReturn;
import net.bytebuddy.instrumentation.method.bytecode.stack.member.MethodVariableAccess;
import net.bytebuddy.instrumentation.type.InstrumentedType;
import net.bytebuddy.instrumentation.type.TypeDescription;
import net.bytebuddy.utility.ByteBuddyCommons;
import org.objectweb.asm.MethodVisitor;

import static net.bytebuddy.matcher.ElementMatchers.*;
import static net.bytebuddy.utility.ByteBuddyCommons.*;

/**
 * Defines a method to access a given field by following the Java bean conventions for getters and setters:
 * <ul>
 * <li>Getter: A method named {@code getFoo()} will be instrumented to read and return the value of a field {@code foo}
 * or another field if one was specified explicitly. If a property is of type {@link java.lang.Boolean} or
 * {@code boolean}, the name {@code isFoo()} is also permitted.</li>
 * <li>Setter: A method named {@code setFoo(value)} will be instrumented to write the given argument {@code value}
 * to a field {@code foo} or to another field if one was specified explicitly.</li>
 * </ul>
 */
public abstract class FieldAccessor implements Instrumentation {

    /**
     * The assigner to use.
     */
    protected final Assigner assigner;

    /**
     * {@code true} if the runtime type of the field's value should be considered when a field
     * is accessed.
     */
    protected final boolean dynamicallyTyped;

    /**
     * Creates a new field accessor.
     *
     * @param assigner         The assigner to use.
     * @param dynamicallyTyped {@code true} if a field value's runtime type should be considered.
     */
    protected FieldAccessor(Assigner assigner, boolean dynamicallyTyped) {
        this.assigner = assigner;
        this.dynamicallyTyped = dynamicallyTyped;
    }

    /**
     * Defines a field accessor where any access is targeted to a field named {@code name}.
     *
     * @param name The name of the field to be accessed.
     * @return A field accessor for a field of a given name.
     */
    public static FieldDefinable ofField(String name) {
        return new ForNamedField(defaultAssigner(), defaultConsiderRuntimeType(), isValidIdentifier(name));
    }

    /**
     * Defines a field accessor where any access is targeted to a field that matches the methods
     * name with the Java specification for bean properties, i.e. a method {@code getFoo} or {@code setFoo(value)}
     * will either read or write a field named {@code foo}.
     *
     * @return A field accessor that follows the Java naming conventions for bean properties.
     */
    public static OwnerTypeLocatable ofBeanProperty() {
        return of(FieldNameExtractor.ForBeanProperty.INSTANCE);
    }

    /**
     * Defines a custom strategy for determining the field that is accessed by this field accessor.
     *
     * @param fieldNameExtractor The field name extractor to use.
     * @return A field accessor using the given field name extractor.
     */
    public static OwnerTypeLocatable of(FieldNameExtractor fieldNameExtractor) {
        return new ForUnnamedField(defaultAssigner(),
                defaultConsiderRuntimeType(),
                nonNull(fieldNameExtractor));
    }

    /**
     * Returns the default assigner that is to be used if no explicit assigner is specified.
     *
     * @return The default assigner that is to be used if no explicit assigner is specified.
     */
    private static Assigner defaultAssigner() {
        return new PrimitiveTypeAwareAssigner(ReferenceTypeAwareAssigner.INSTANCE);
    }

    /**
     * Returns the default value for considering the runtime type when using an assigner.
     *
     * @return The default value for considering the runtime type when using an assigner.
     */
    private static boolean defaultConsiderRuntimeType() {
        return false;
    }

    /**
     * Applies a field getter instrumentation.
     *
     * @param methodVisitor          The method visitor to write any instructions to.
     * @param instrumentationContext The instrumentation context of the current instrumentation.
     * @param fieldDescription       The description of the field to read.
     * @param methodDescription      The method that is target of the instrumentation.
     * @return The required size of the operand stack and local variable array for this instrumentation.
     */
    protected ByteCodeAppender.Size applyGetter(MethodVisitor methodVisitor,
                                                Instrumentation.Context instrumentationContext,
                                                FieldDescription fieldDescription,
                                                MethodDescription methodDescription) {
        StackManipulation stackManipulation = assigner.assign(fieldDescription.getFieldType(),
                methodDescription.getReturnType(),
                dynamicallyTyped);
        if (!stackManipulation.isValid()) {
            throw new IllegalStateException("Getter type of " + methodDescription + " is not compatible with " + fieldDescription);
        }
        return apply(methodVisitor,
                instrumentationContext,
                fieldDescription,
                methodDescription,
                new StackManipulation.Compound(
                        FieldAccess.forField(fieldDescription).getter(),
                        stackManipulation
                )
        );
    }

    /**
     * Applies a field setter instrumentation.
     *
     * @param methodVisitor          The method visitor to write any instructions to.
     * @param instrumentationContext The instrumentation context of the current instrumentation.
     * @param fieldDescription       The description of the field to write to.
     * @param methodDescription      The method that is target of the instrumentation.
     * @return The required size of the operand stack and local variable array for this instrumentation.
     */
    protected ByteCodeAppender.Size applySetter(MethodVisitor methodVisitor,
                                                Instrumentation.Context instrumentationContext,
                                                FieldDescription fieldDescription,
                                                MethodDescription methodDescription) {
        StackManipulation stackManipulation = assigner.assign(methodDescription.getParameterTypes().get(0),
                fieldDescription.getFieldType(),
                dynamicallyTyped);
        if (!stackManipulation.isValid()) {
            throw new IllegalStateException("Setter type of " + methodDescription + " is not compatible with " + fieldDescription);
        } else if (fieldDescription.isFinal()) {
            throw new IllegalArgumentException("Cannot apply setter on final field " + fieldDescription);
        }
        return apply(methodVisitor,
                instrumentationContext,
                fieldDescription,
                methodDescription,
                new StackManipulation.Compound(
                        MethodVariableAccess.forType(fieldDescription.getFieldType())
                                .loadFromIndex(methodDescription.getParameterOffset(0)),
                        stackManipulation,
                        FieldAccess.forField(fieldDescription).putter()
                )
        );
    }

    /**
     * A generic implementation of the application of a {@link net.bytebuddy.instrumentation.method.bytecode.ByteCodeAppender}.
     *
     * @param methodVisitor          The method visitor to write any instructions to.
     * @param instrumentationContext The instrumentation context of the current instrumentation.
     * @param fieldDescription       The description of the field to access.
     * @param methodDescription      The method that is target of the instrumentation.
     * @param fieldAccess            The manipulation that represents the field access.
     * @return A suitable {@link net.bytebuddy.instrumentation.method.bytecode.ByteCodeAppender}.
     */
    private ByteCodeAppender.Size apply(MethodVisitor methodVisitor,
                                        Instrumentation.Context instrumentationContext,
                                        FieldDescription fieldDescription,
                                        MethodDescription methodDescription,
                                        StackManipulation fieldAccess) {
        if (methodDescription.isStatic() && !fieldDescription.isStatic()) {
            throw new IllegalArgumentException("Cannot call instance field "
                    + fieldDescription + " from static method " + methodDescription);
        }
        StackManipulation.Size stackSize = new StackManipulation.Compound(
                fieldDescription.isStatic()
                        ? StackManipulation.LegalTrivial.INSTANCE
                        : MethodVariableAccess.REFERENCE.loadFromIndex(0),
                fieldAccess,
                MethodReturn.returning(methodDescription.getReturnType())
        ).apply(methodVisitor, instrumentationContext);
        return new ByteCodeAppender.Size(stackSize.getMaximalSize(), methodDescription.getStackSize());
    }

    /**
     * Locates a field's name.
     *
     * @param targetMethod The method that is target of the instrumentation.
     * @return The name of the field to be located for this instrumentation.
     */
    protected abstract String getFieldName(MethodDescription targetMethod);

    @Override
    public boolean equals(Object other) {
        return this == other || !(other == null || getClass() != other.getClass())
                && dynamicallyTyped == ((FieldAccessor) other).dynamicallyTyped
                && assigner.equals(((FieldAccessor) other).assigner);
    }

    @Override
    public int hashCode() {
        return 31 * assigner.hashCode() + (dynamicallyTyped ? 1 : 0);
    }

    /**
     * A field locator allows to determine a field name for a given method.
     */
    public static interface FieldLocator {

        /**
         * Locates a field of a given name or throws an exception if no field with such a name exists.
         *
         * @param name The name of the field to locate.
         * @return A representation of this field.
         */
        FieldDescription locate(String name);

        /**
         * A factory that only looks up fields in the instrumented type.
         */
        static enum ForInstrumentedType implements Factory {

            /**
             * The singleton instance.
             */
            INSTANCE;

            @Override
            public FieldLocator make(TypeDescription instrumentedType) {
                return new ForGivenType(instrumentedType, instrumentedType);
            }
        }

        /**
         * A factory for creating a {@link net.bytebuddy.instrumentation.FieldAccessor.FieldLocator}.
         */
        static interface Factory {

            /**
             * Creates a field locator.
             *
             * @param instrumentedType The instrumented type onto which the field locator is to be applied.
             * @return The field locator for locating fields on a given type.
             */
            FieldLocator make(TypeDescription instrumentedType);
        }

        /**
         * A field locator that finds a type by traversing the type hierarchy beginning with fields defined
         * in the most specific subclass traversing the class hierarchy down to the least specific type.
         * This emulates the Java language's field access where fields are shadowed when an extending class defines
         * a field with identical name.
         */
        static class ForInstrumentedTypeHierarchy implements FieldLocator {

            /**
             * The instrumented type for which a field is located.
             */
            private final TypeDescription instrumentedType;

            /**
             * Creates a field locator that follows the type hierarchy.
             *
             * @param instrumentedType The instrumented type onto which the field locator is to be applied.
             */
            public ForInstrumentedTypeHierarchy(TypeDescription instrumentedType) {
                this.instrumentedType = instrumentedType;
            }

            @Override
            public FieldDescription locate(String name) {
                TypeDescription currentType = instrumentedType;
                do {
                    FieldList fieldList = currentType.getDeclaredFields().filter(named(name).and(isVisibleTo(instrumentedType)));
                    if (fieldList.size() == 1) {
                        return fieldList.getOnly();
                    }
                } while (!(currentType = currentType.getSupertype()).represents(Object.class));
                throw new IllegalArgumentException("There is no field '" + name + " that is visible to " + instrumentedType);
            }

            @Override
            public boolean equals(Object other) {
                return this == other || !(other == null || getClass() != other.getClass())
                        && instrumentedType.equals(((ForInstrumentedTypeHierarchy) other).instrumentedType);
            }

            @Override
            public int hashCode() {
                return instrumentedType.hashCode();
            }

            @Override
            public String toString() {
                return "FieldAccessor.FieldLocator.ForInstrumentedTypeHierarchy{instrumentedType=" + instrumentedType + '}';
            }

            /**
             * A field locator factory creating a
             * {@link net.bytebuddy.instrumentation.FieldAccessor.FieldLocator.ForInstrumentedTypeHierarchy}.
             */
            public static enum Factory implements FieldLocator.Factory {

                /**
                 * The singleton instance.
                 */
                INSTANCE;

                @Override
                public FieldLocator make(TypeDescription instrumentedType) {
                    return new ForInstrumentedTypeHierarchy(instrumentedType);
                }
            }
        }

        /**
         * A field locator that only looks up fields that are defined for a given type.
         */
        static class ForGivenType implements FieldLocator {

            /**
             * The target type for which a field should be accessed.
             */
            private final TypeDescription targetType;
            /**
             * The instrumented type onto which the field locator is to be applied.
             */
            private final TypeDescription instrumentedType;

            /**
             * Creates a new field locator for a given type.
             *
             * @param targetType       The type for which fields are to be looked up.
             * @param instrumentedType The instrumented type onto which the field locator is to be applied.
             */
            public ForGivenType(TypeDescription targetType, TypeDescription instrumentedType) {
                this.targetType = targetType;
                this.instrumentedType = instrumentedType;
            }

            @Override
            public FieldDescription locate(String name) {
                FieldList fieldList = targetType.getDeclaredFields().filter(named(name).and(isVisibleTo(instrumentedType)));
                if (fieldList.size() != 1) {
                    throw new IllegalArgumentException("No field named " + name + " on " + targetType + " is visible to " + instrumentedType);
                }
                return fieldList.getOnly();
            }

            @Override
            public boolean equals(Object other) {
                return this == other || !(other == null || getClass() != other.getClass())
                        && instrumentedType.equals(((ForGivenType) other).instrumentedType)
                        && targetType.equals(((ForGivenType) other).targetType);
            }

            @Override
            public int hashCode() {
                return 31 * instrumentedType.hashCode() + targetType.hashCode();
            }

            @Override
            public String toString() {
                return "FieldAccessor.FieldLocator.ForGivenType{" +
                        "targetType=" + targetType +
                        ", instrumentedType=" + instrumentedType +
                        '}';
            }

            /**
             * A factory for a field locator locating given type.
             */
            public static class Factory implements FieldLocator.Factory {

                /**
                 * The type to locate.
                 */
                private final TypeDescription targetType;

                /**
                 * Creates a new field locator factory for a given type.
                 *
                 * @param targetType The type for which fields are to be looked up.
                 */
                public Factory(TypeDescription targetType) {
                    this.targetType = targetType;
                }

                @Override
                public FieldLocator make(TypeDescription instrumentedType) {
                    return new ForGivenType(targetType, instrumentedType);
                }

                @Override
                public boolean equals(Object other) {
                    return this == other || !(other == null || getClass() != other.getClass())
                            && targetType.equals(((Factory) other).targetType);
                }

                @Override
                public int hashCode() {
                    return targetType.hashCode();
                }

                @Override
                public String toString() {
                    return "FieldAccessor.FieldLocator.ForGivenType.Factory{targetType=" + targetType + '}';
                }
            }
        }
    }

    /**
     * A field name extractor is responsible for determining a field name to a method that is implemented
     * to access this method.
     */
    public static interface FieldNameExtractor {

        /**
         * Extracts a field name to be accessed by a getter or setter method.
         *
         * @param methodDescription The method for which a field name is to be determined.
         * @return The name of the field to be accessed by this method.
         */
        String fieldNameFor(MethodDescription methodDescription);

        /**
         * A {@link net.bytebuddy.instrumentation.FieldAccessor.FieldNameExtractor} that determines a field name
         * according to the rules of Java bean naming conventions.
         */
        static enum ForBeanProperty implements FieldNameExtractor {

            /**
             * The singleton instance.
             */
            INSTANCE;

            @Override
            public String fieldNameFor(MethodDescription methodDescription) {
                String name = methodDescription.getInternalName();
                int crop;
                if (name.startsWith("get") || name.startsWith("set")) {
                    crop = 3;
                } else if (name.startsWith("is")) {
                    crop = 2;
                } else {
                    throw new IllegalArgumentException(methodDescription + " does not follow Java bean naming conventions");
                }
                name = name.substring(crop);
                if (name.length() == 0) {
                    throw new IllegalArgumentException(methodDescription + " does not specify a bean name");
                }
                return Character.toLowerCase(name.charAt(0)) + name.substring(1);
            }
        }
    }

    /**
     * A field accessor that can be configured to use a given assigner and runtime type use configuration.
     */
    public static interface AssignerConfigurable extends Instrumentation {

        /**
         * Returns a field accessor that is identical to this field accessor but uses the given assigner
         * and runtime type use configuration.
         *
         * @param assigner         The assigner to use.
         * @param dynamicallyTyped {@code true} if a field value's runtime type should be considered.
         * @return This field accessor with the given assigner and runtime type use configuration.
         */
        Instrumentation withAssigner(Assigner assigner, boolean dynamicallyTyped);
    }

    /**
     * A field accessor that can be configured to locate a field in a specific manner.
     */
    public static interface OwnerTypeLocatable extends AssignerConfigurable {

        /**
         * Determines that a field should only be considered when it was defined in a given type.
         *
         * @param type The type to be considered.
         * @return This field accessor which will only considered fields that are defined in the given type.
         */
        AssignerConfigurable in(Class<?> type);

        /**
         * Determines that a field should only be considered when it was defined in a given type.
         *
         * @param typeDescription A description of the type to be considered.
         * @return This field accessor which will only considered fields that are defined in the given type.
         */
        AssignerConfigurable in(TypeDescription typeDescription);

        /**
         * Determines that a field should only be considered when it was identified by a field locator that is
         * produced by the given factory.
         *
         * @param fieldLocatorFactory A factory that will produce a field locator that will be used to find locate
         *                            a field to be accessed.
         * @return This field accessor which will only considered fields that are defined in the given type.
         */
        AssignerConfigurable in(FieldLocator.Factory fieldLocatorFactory);
    }

    /**
     * Determines a field accessor that accesses a field of a given name which might not yet be
     * defined.
     */
    public static interface FieldDefinable extends OwnerTypeLocatable {

        /**
         * Defines a field with the given name in the instrumented type.
         *
         * @param type     The type of the field.
         * @param modifier The modifiers for the field.
         * @return A field accessor that defines a field of the given type.
         */
        AssignerConfigurable defineAs(Class<?> type, ModifierContributor.ForField... modifier);

        /**
         * Defines a field with the given name in the instrumented type.
         *
         * @param typeDescription The type of the field.
         * @param modifier        The modifiers for the field.
         * @return A field accessor that defines a field of the given type.
         */
        AssignerConfigurable defineAs(TypeDescription typeDescription, ModifierContributor.ForField... modifier);
    }

    /**
     * Implementation of a field accessor instrumentation where a field is identified by a method's name following
     * the Java specification for bean properties.
     */
    protected static class ForUnnamedField extends FieldAccessor implements OwnerTypeLocatable {

        /**
         * A factory for creating a field locator for implementing this field accessor.
         */
        private final FieldLocator.Factory fieldLocatorFactory;

        /**
         * The field name extractor to be used.
         */
        private final FieldNameExtractor fieldNameExtractor;

        /**
         * Creates a new field accessor instrumentation.
         *
         * @param assigner           The assigner to use.
         * @param dynamicallyTyped   {@code true} if a field value's runtime type should be considered.
         * @param fieldNameExtractor The field name extractor to use.
         */
        protected ForUnnamedField(Assigner assigner,
                                  boolean dynamicallyTyped,
                                  FieldNameExtractor fieldNameExtractor) {
            this(assigner,
                    dynamicallyTyped,
                    fieldNameExtractor,
                    FieldLocator.ForInstrumentedTypeHierarchy.Factory.INSTANCE);
        }

        /**
         * Creates a new field accessor instrumentation.
         *
         * @param assigner            The assigner to use.
         * @param dynamicallyTyped    {@code true} if a field value's runtime type should be considered.
         * @param fieldNameExtractor  The field name extractor to use.
         * @param fieldLocatorFactory A factory that will produce a field locator that will be used to find locate
         *                            a field to be accessed.
         */
        protected ForUnnamedField(Assigner assigner,
                                  boolean dynamicallyTyped,
                                  FieldNameExtractor fieldNameExtractor,
                                  FieldLocator.Factory fieldLocatorFactory) {
            super(assigner, dynamicallyTyped);
            this.fieldNameExtractor = fieldNameExtractor;
            this.fieldLocatorFactory = fieldLocatorFactory;
        }

        @Override
        public AssignerConfigurable in(FieldLocator.Factory fieldLocatorFactory) {
            return new ForUnnamedField(assigner, dynamicallyTyped, fieldNameExtractor, nonNull(fieldLocatorFactory));
        }

        @Override
        public AssignerConfigurable in(Class<?> type) {
            return in(new TypeDescription.ForLoadedType(nonNull(type)));
        }

        @Override
        public AssignerConfigurable in(TypeDescription typeDescription) {
            return nonNull(typeDescription).represents(TargetType.class)
                    ? in(FieldLocator.ForInstrumentedType.INSTANCE)
                    : in(new FieldLocator.ForGivenType.Factory(typeDescription));
        }

        @Override
        public Instrumentation withAssigner(Assigner assigner, boolean dynamicallyTyped) {
            return new ForUnnamedField(nonNull(assigner), dynamicallyTyped, fieldNameExtractor, fieldLocatorFactory);
        }

        @Override
        public InstrumentedType prepare(InstrumentedType instrumentedType) {
            return instrumentedType;
        }

        @Override
        public ByteCodeAppender appender(Target instrumentationTarget) {
            return new Appender(fieldLocatorFactory.make(instrumentationTarget.getTypeDescription()));
        }

        @Override
        protected String getFieldName(MethodDescription targetMethod) {
            return fieldNameExtractor.fieldNameFor(targetMethod);
        }

        @Override
        public boolean equals(Object other) {
            return this == other || !(other == null || getClass() != other.getClass())
                    && super.equals(other)
                    && fieldNameExtractor.equals(((ForUnnamedField) other).fieldNameExtractor)
                    && fieldLocatorFactory.equals(((ForUnnamedField) other).fieldLocatorFactory);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * super.hashCode() + fieldLocatorFactory.hashCode()) + fieldNameExtractor.hashCode();
        }

        @Override
        public String toString() {
            return "FieldAccessor.ForUnnamedField{" +
                    "assigner=" + assigner +
                    "dynamicallyTyped=" + dynamicallyTyped +
                    "fieldLocatorFactory=" + fieldLocatorFactory +
                    "fieldNameExtractor=" + fieldNameExtractor +
                    '}';
        }
    }

    /**
     * Implementation of a field accessor instrumentation where the field name is given explicitly.
     */
    protected static class ForNamedField extends FieldAccessor implements FieldDefinable {

        /**
         * The name of the field that is accessed.
         */
        private final String fieldName;

        /**
         * The preparation handler for implementing this field accessor.
         */
        private final PreparationHandler preparationHandler;

        /**
         * The field locator factory for implementing this field accessor.
         */
        private final FieldLocator.Factory fieldLocatorFactory;

        /**
         * Creates a field accessor instrumentation for a field of a given name.
         *
         * @param assigner         The assigner to use.
         * @param dynamicallyTyped {@code true} if a field value's runtime type should be considered.
         * @param fieldName        The name of the field.
         */
        protected ForNamedField(Assigner assigner,
                                boolean dynamicallyTyped,
                                String fieldName) {
            super(assigner, dynamicallyTyped);
            this.fieldName = fieldName;
            preparationHandler = PreparationHandler.NoOp.INSTANCE;
            fieldLocatorFactory = FieldLocator.ForInstrumentedTypeHierarchy.Factory.INSTANCE;
        }

        /**
         * reates a field accessor instrumentation for a field of a given name.
         *
         * @param fieldName           The name of the field.
         * @param preparationHandler  The preparation handler for potentially defining a field.
         * @param fieldLocatorFactory A factory that will produce a field locator that will be used to find locate
         *                            a field to be accessed.
         * @param assigner            The assigner to use.
         * @param dynamicallyTyped    {@code true} if a field value's runtime type should be considered.
         */
        private ForNamedField(Assigner assigner,
                              boolean dynamicallyTyped,
                              String fieldName,
                              PreparationHandler preparationHandler,
                              FieldLocator.Factory fieldLocatorFactory) {
            super(assigner, dynamicallyTyped);
            this.fieldName = fieldName;
            this.preparationHandler = preparationHandler;
            this.fieldLocatorFactory = fieldLocatorFactory;
        }

        @Override
        public AssignerConfigurable defineAs(Class<?> type, ModifierContributor.ForField... modifier) {
            return defineAs(new TypeDescription.ForLoadedType(nonNull(type)), modifier);
        }

        @Override
        public AssignerConfigurable defineAs(TypeDescription typeDescription, ModifierContributor.ForField... modifier) {
            return new ForNamedField(assigner,
                    dynamicallyTyped,
                    fieldName,
                    new PreparationHandler.FieldDefiner(fieldName, nonVoid(typeDescription), nonNull(modifier)),
                    FieldLocator.ForInstrumentedType.INSTANCE);
        }

        @Override
        public AssignerConfigurable in(FieldLocator.Factory fieldLocatorFactory) {
            return new ForNamedField(assigner,
                    dynamicallyTyped,
                    fieldName,
                    preparationHandler,
                    nonNull(fieldLocatorFactory));
        }

        @Override
        public AssignerConfigurable in(Class<?> type) {
            return in(new TypeDescription.ForLoadedType(nonNull(type)));
        }

        @Override
        public AssignerConfigurable in(TypeDescription typeDescription) {
            return typeDescription.represents(TargetType.class)
                    ? in(FieldLocator.ForInstrumentedType.INSTANCE)
                    : in(new FieldLocator.ForGivenType.Factory(typeDescription));
        }

        @Override
        public Instrumentation withAssigner(Assigner assigner, boolean dynamicallyTyped) {
            return new ForNamedField(nonNull(assigner),
                    dynamicallyTyped,
                    fieldName,
                    preparationHandler,
                    fieldLocatorFactory);
        }

        @Override
        public InstrumentedType prepare(InstrumentedType instrumentedType) {
            return preparationHandler.prepare(instrumentedType);
        }

        @Override
        public ByteCodeAppender appender(Target instrumentationTarget) {
            return new Appender(fieldLocatorFactory.make(instrumentationTarget.getTypeDescription()));
        }

        @Override
        protected String getFieldName(MethodDescription targetMethod) {
            return fieldName;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (other == null || getClass() != other.getClass()) return false;
            if (!super.equals(other)) return false;
            ForNamedField that = (ForNamedField) other;
            return fieldLocatorFactory.equals(that.fieldLocatorFactory)
                    && fieldName.equals(that.fieldName)
                    && preparationHandler.equals(that.preparationHandler);
        }

        @Override
        public int hashCode() {
            int result = super.hashCode();
            result = 31 * result + fieldName.hashCode();
            result = 31 * result + preparationHandler.hashCode();
            result = 31 * result + fieldLocatorFactory.hashCode();
            return result;
        }

        @Override
        public String toString() {
            return "FieldAccessor.ForNamedField{" +
                    "assigner=" + assigner +
                    "dynamicallyTyped=" + dynamicallyTyped +
                    "fieldName='" + fieldName + '\'' +
                    ", preparationHandler=" + preparationHandler +
                    ", fieldLocatorFactory=" + fieldLocatorFactory +
                    '}';
        }

        /**
         * A preparation handler is responsible for defining a field value on an instrumentation, if necessary.
         */
        private static interface PreparationHandler {

            /**
             * Prepares the instrumented type.
             *
             * @param instrumentedType The instrumented type to be prepared.
             * @return The readily prepared instrumented type.
             */
            InstrumentedType prepare(InstrumentedType instrumentedType);

            /**
             * A non-operational preparation handler that does not alter the field.
             */
            static enum NoOp implements PreparationHandler {

                /**
                 * The singleton instance.
                 */
                INSTANCE;

                @Override
                public InstrumentedType prepare(InstrumentedType instrumentedType) {
                    return instrumentedType;
                }
            }

            /**
             * A preparation handler that actually defines a field on an instrumented type.
             */
            static class FieldDefiner implements PreparationHandler {

                /**
                 * The name of the field that is defined by this preparation handler.
                 */
                private final String name;

                /**
                 * The type of the field that is to be defined.
                 */
                private final TypeDescription typeDescription;

                /**
                 * The modifier of the field that is to be defined.
                 */
                private final int modifiers;

                /**
                 * Creates a new preparation handler that defines a given field.
                 *
                 * @param name            The name of the field that is defined by this preparation handler.
                 * @param typeDescription The type of the field that is to be defined.
                 * @param contributor     The modifier of the field that is to be defined.
                 */
                public FieldDefiner(String name, TypeDescription typeDescription, ModifierContributor.ForField... contributor) {
                    this.name = name;
                    this.typeDescription = typeDescription;
                    modifiers = resolveModifierContributors(ByteBuddyCommons.FIELD_MODIFIER_MASK, contributor);
                }

                @Override
                public InstrumentedType prepare(InstrumentedType instrumentedType) {
                    return instrumentedType.withField(name,
                            typeDescription.represents(TargetType.class) ? instrumentedType : typeDescription,
                            modifiers);
                }

                @Override
                public boolean equals(Object other) {
                    if (this == other) return true;
                    if (other == null || getClass() != other.getClass()) return false;
                    FieldDefiner that = (FieldDefiner) other;
                    return modifiers == that.modifiers
                            && name.equals(that.name)
                            && typeDescription.equals(that.typeDescription);
                }

                @Override
                public int hashCode() {
                    int result = name.hashCode();
                    result = 31 * result + typeDescription.hashCode();
                    result = 31 * result + modifiers;
                    return result;
                }

                @Override
                public String toString() {
                    return "FieldAccessor.ForNamedField.PreparationHandler.FieldDefiner{" +
                            "name='" + name + '\'' +
                            ", typeDescription=" + typeDescription +
                            ", modifiers=" + modifiers +
                            '}';
                }
            }
        }
    }

    /**
     * An byte code appender for an field accessor instrumentation.
     */
    protected class Appender implements ByteCodeAppender {

        /**
         * The field locator for implementing this appender.
         */
        private final FieldLocator fieldLocator;

        /**
         * Creates a new byte code appender for a field accessor instrumentation.
         *
         * @param fieldLocator The field locator for this byte code appender.
         */
        protected Appender(FieldLocator fieldLocator) {
            this.fieldLocator = fieldLocator;
        }

        @Override
        public boolean appendsCode() {
            return true;
        }

        @Override
        public Size apply(MethodVisitor methodVisitor,
                          Instrumentation.Context instrumentationContext,
                          MethodDescription instrumentedMethod) {
            if (isConstructor().matches(instrumentedMethod)) {
                throw new IllegalArgumentException("Constructors cannot define beans: " + instrumentedMethod);
            }
            if (takesArguments(0).and(not(returns(void.class))).matches(instrumentedMethod)) {
                return applyGetter(methodVisitor,
                        instrumentationContext,
                        fieldLocator.locate(getFieldName(instrumentedMethod)),
                        instrumentedMethod);
            } else if (takesArguments(1).and(returns(void.class)).matches(instrumentedMethod)) {
                return applySetter(methodVisitor,
                        instrumentationContext,
                        fieldLocator.locate(getFieldName(instrumentedMethod)),
                        instrumentedMethod);
            } else {
                throw new IllegalArgumentException("Method " + instrumentationContext + " is no bean property");
            }
        }

        /**
         * Returns the outer instance.
         *
         * @return The outer instance.
         */
        private FieldAccessor getFieldAccessor() {
            return FieldAccessor.this;
        }

        @Override
        public boolean equals(Object other) {
            return this == other || !(other == null || getClass() != other.getClass())
                    && fieldLocator.equals(((Appender) other).fieldLocator)
                    && FieldAccessor.this.equals(((Appender) other).getFieldAccessor());
        }

        @Override
        public int hashCode() {
            return 31 * FieldAccessor.this.hashCode() + fieldLocator.hashCode();
        }

        @Override
        public String toString() {
            return "FieldAccessor.Appender{" +
                    "fieldLocator=" + fieldLocator +
                    "fieldAccessor=" + FieldAccessor.this +
                    '}';
        }
    }
}