package io.quarkus.arc.impl;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.Annotated;
import jakarta.enterprise.inject.spi.AnnotatedCallable;
import jakarta.enterprise.inject.spi.AnnotatedConstructor;
import jakarta.enterprise.inject.spi.AnnotatedField;
import jakarta.enterprise.inject.spi.AnnotatedMethod;
import jakarta.enterprise.inject.spi.AnnotatedParameter;
import jakarta.enterprise.inject.spi.AnnotatedType;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.InjectionPoint;

import io.quarkus.arc.InjectableBean;
import io.quarkus.arc.InjectableReferenceProvider;

/**
 * Set the current {@link InjectionPoint} during creation of a dependent bean.
 */
public class CurrentInjectionPointProvider<T> implements InjectableReferenceProvider<T> {

    static final InjectionPoint EMPTY = new InjectionPointImpl(Object.class, Object.class, Collections.emptySet(), null, null,
            null, -1, false);

    static final Supplier<InjectionPoint> EMPTY_SUPPLIER = new Supplier<InjectionPoint>() {

        @Override
        public InjectionPoint get() {
            return CurrentInjectionPointProvider.EMPTY;
        }
    };

    private final Supplier<InjectableReferenceProvider<T>> delegateSupplier;

    private final InjectionPoint injectionPoint;

    public CurrentInjectionPointProvider(InjectableBean<?> bean, Supplier<InjectableReferenceProvider<T>> delegateSupplier,
            Type requiredType, Set<Annotation> qualifiers, Set<Annotation> annotations, Member javaMember, int position,
            boolean isTransient) {
        this.delegateSupplier = delegateSupplier;
        this.injectionPoint = new InjectionPointImpl(requiredType, requiredType, qualifiers, bean, annotations, javaMember,
                position, isTransient);
    }

    @Override
    public T get(CreationalContext<T> creationalContext) {
        InjectionPoint prev = InjectionPointProvider.set(injectionPoint);
        try {
            return delegateSupplier.get().get(creationalContext);
        } finally {
            InjectionPointProvider.set(prev);
        }
    }

    InjectableReferenceProvider<T> getDelegate() {
        return delegateSupplier.get();
    }

    public static class InjectionPointImpl implements InjectionPoint {
        private final Type requiredType;
        private final Set<Annotation> qualifiers;
        private final InjectableBean<?> bean;
        private final Annotated annotated;
        private final Member member;
        private final boolean isTransient;

        public InjectionPointImpl(Type injectionPointType, Type requiredType, Set<Annotation> qualifiers,
                InjectableBean<?> bean, Set<Annotation> annotations, Member javaMember,
                int position, boolean isTransient) {
            this.requiredType = requiredType;
            this.qualifiers = qualifiers;
            this.bean = bean;
            if (javaMember instanceof Executable) {
                this.annotated = new AnnotatedParameterImpl<>(injectionPointType, annotations, position,
                        (Executable) javaMember);
            } else if (javaMember instanceof Field) {
                this.annotated = new AnnotatedFieldImpl<>(injectionPointType, annotations, (Field) javaMember);
            } else {
                this.annotated = null;
            }
            this.member = javaMember;
            this.isTransient = isTransient;
        }

        @Override
        public Type getType() {
            return requiredType;
        }

        @Override
        public Set<Annotation> getQualifiers() {
            return qualifiers;
        }

        @Override
        public Bean<?> getBean() {
            return bean;
        }

        @Override
        public Member getMember() {
            return member;
        }

        @Override
        public Annotated getAnnotated() {
            return annotated;
        }

        @Override
        public boolean isDelegate() {
            return false;
        }

        @Override
        public boolean isTransient() {
            return isTransient;
        }

    }

    static class AnnotatedFieldImpl<X> extends AnnotatedBase implements AnnotatedField<X> {

        private final Field field;

        AnnotatedFieldImpl(Type baseType, Set<Annotation> annotations, Field field) {
            super(baseType, annotations);
            this.field = field;
        }

        @Override
        public boolean isStatic() {
            return Modifier.isStatic(field.getModifiers());
        }

        @SuppressWarnings("unchecked")
        @Override
        public AnnotatedType<X> getDeclaringType() {
            return new AnnotatedTypeImpl<>((Class<X>) field.getDeclaringClass());
        }

        @Override
        public Field getJavaMember() {
            return field;
        }

    }

    static class AnnotatedParameterImpl<X> extends AnnotatedBase implements AnnotatedParameter<X> {

        private final int position;
        private final Executable executable;

        AnnotatedParameterImpl(Type baseType, Set<Annotation> annotations, int position, Executable executable) {
            super(baseType, annotations);
            this.position = position;
            this.executable = executable;
        }

        @Override
        public int getPosition() {
            return position;
        }

        @SuppressWarnings("unchecked")
        @Override
        public AnnotatedCallable<X> getDeclaringCallable() {
            if (executable instanceof Method) {
                return new AnnotatedMethodImpl<>((Method) executable);
            } else {
                return new AnnotatedConstructorImpl<X>((Constructor<X>) executable);
            }
        }

    }

    static abstract class AnnotatedBase implements Annotated {

        private final Type baseType;
        private final Set<Annotation> annotations;

        AnnotatedBase(Type baseType, Set<Annotation> annotations) {
            this.baseType = baseType;
            this.annotations = CollectionHelpers.toImmutableSmallSet(annotations);
        }

        @Override
        public Type getBaseType() {
            return baseType;
        }

        @Override
        public Set<Type> getTypeClosure() {
            throw new UnsupportedOperationException();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T extends Annotation> T getAnnotation(Class<T> annotationType) {
            if (annotations == null) {
                throw new UnsupportedOperationException();
            }
            for (Annotation annotation : annotations) {
                if (annotation.annotationType().equals(annotationType)) {
                    return (T) annotation;
                }
            }
            return null;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T extends Annotation> Set<T> getAnnotations(Class<T> annotationType) {
            if (annotations == null) {
                throw new UnsupportedOperationException();
            }
            Set<T> found = new HashSet<>();
            for (Annotation annotation : annotations) {
                if (annotation.annotationType().equals(annotationType)) {
                    found.add((T) annotation);
                }
            }
            return found;
        }

        @Override
        public Set<Annotation> getAnnotations() {
            if (annotations == null) {
                throw new UnsupportedOperationException();
            }
            return annotations;
        }

        @Override
        public boolean isAnnotationPresent(Class<? extends Annotation> annotationType) {
            return getAnnotation(annotationType) != null;
        }

    }

    static class AnnotatedTypeImpl<X> extends AnnotatedBase implements AnnotatedType<X> {

        private final Class<X> clazz;

        AnnotatedTypeImpl(Class<X> clazz) {
            super(clazz, null);
            this.clazz = clazz;
        }

        @Override
        public Class<X> getJavaClass() {
            return clazz;
        }

        @Override
        public Set<AnnotatedConstructor<X>> getConstructors() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<AnnotatedMethod<? super X>> getMethods() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<AnnotatedField<? super X>> getFields() {
            throw new UnsupportedOperationException();
        }

    }

    static class AnnotatedMethodImpl<X> extends AnnotatedBase implements AnnotatedMethod<X> {

        private final Method method;

        AnnotatedMethodImpl(Method method) {
            super(method.getGenericReturnType(), null);
            this.method = method;
        }

        @Override
        public List<AnnotatedParameter<X>> getParameters() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isStatic() {
            return Modifier.isStatic(method.getModifiers());
        }

        @SuppressWarnings("unchecked")
        @Override
        public AnnotatedType<X> getDeclaringType() {
            return new AnnotatedTypeImpl<>((Class<X>) method.getDeclaringClass());
        }

        @Override
        public Method getJavaMember() {
            return method;
        }

    }

    static class AnnotatedConstructorImpl<X> extends AnnotatedBase implements AnnotatedConstructor<X> {

        private final Constructor<X> constructor;

        public AnnotatedConstructorImpl(Constructor<X> constructor) {
            super(constructor.getDeclaringClass(), null);
            this.constructor = constructor;
        }

        @Override
        public List<AnnotatedParameter<X>> getParameters() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isStatic() {
            return false;
        }

        @Override
        public AnnotatedType<X> getDeclaringType() {
            return new AnnotatedTypeImpl<>((Class<X>) constructor.getDeclaringClass());
        }

        @Override
        public Constructor<X> getJavaMember() {
            return constructor;
        }

    }

}
