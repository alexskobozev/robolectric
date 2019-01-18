package org.robolectric.util.inject;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.GuardedBy;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

/**
 * A tiny dependency injection and plugin helper for Robolectric.
 *
 * Dependencies may be retrieved explicitly by calling {@link #getInstance(Type)}; transitive
 * dependencies will be automatically injected as needed.
 *
 * Dependencies are identified by an interface or class, and optionally by a name specified with
 * {@link Named}.
 *
 * When a dependency is requested, the injector looks for any instance that has been previously
 * found for the given interface, or that has been explicitly registered with
 * {@link Builder#bind(Class, Object)} or {@link Builder#bind(Key, Object)}. Failing that, the
 * injector searches for an implementing class from the following sources, in order:
 *
 * * Explicitly-registered implementations registered with {@link Builder#bind(Class, Class)}.
 * * If the dependency type is an array or {@link Collection}, then the component type
 *   of the array or collection is recursively sought using {@link PluginFinder#findPlugins(Class)}
 *   and an array or collection of those instances is returned.
 * * Plugin implementations published as {@link java.util.ServiceLoader} services under the
 *   dependency type (see also {@link PluginFinder#findPlugin(Class)}).
 * * Fallback default implementation classes registered with
 *   {@link Builder#bindDefault(Class, Class)}.
 * * If the dependency type is an interface annotated {@link AutoFactory}, then a factory object
 *   implementing that interface is created; a new scoped injector is created for every method
 *   call to the factory, with parameter arguments registered on the scoped injector.
 * * If the dependency type is a concrete class, then the dependency type itself.
 * * If this is a scoped injector, then the parent injector is asked for an implementation.
 * * If no implementation has yet been found, the injector will throw an exception.
 *
 * When the injector has determined an implementing class, it attempts to instantiate it. It
 * searches for a constructor in the following order:
 *
 * * A singular public constructor annotated {@link Inject}. (If multiple constructors are
 *   `@Inject` annotated, the injector will throw an exception.)
 * * A singular public constructor of any arity.
 * * If no constructor has yet been found, the injector will throw an exception.
 *
 * Any constructor parameters are seen as further dependencies, and the injector will recursively
 * attempt to resolve an implementation for each before invoking the constructor and thereby
 * instantiating the original dependency implementation.
 *
 * For a given injector, all calls to {@link #getInstance} are idempotent.
 *
 * All methods are MT-safe.
 */
@SuppressWarnings({"NewApi", "AndroidJdkLibsChecker"})
public class Injector {

  private final Injector superInjector;
  private final PluginFinder pluginFinder = new PluginFinder();

  @GuardedBy("this")
  private final Map<Key<?>, Provider<?>> providers;
  private final Map<Key<?>, Class<?>> defaultImpls;

  /** Creates a new empty injector. */
  public Injector() {
    this(null, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
  }

  private Injector(Injector superInjector, Map<Key<?>, Provider<?>> providers,
      Map<Key<?>, Class<?>> explicitImpls, Map<Key<?>, Class<?>> defaultImpls) {
    this.superInjector = superInjector;
    this.providers = new HashMap<>(providers);
    this.defaultImpls = new HashMap<>(defaultImpls);

    for (Map.Entry<Key<?>, Class<?>> e : explicitImpls.entrySet()) {
      Key key = e.getKey();
      Provider tProvider = () -> inject(e.getValue());
      //noinspection unchecked
      registerMemoized(key, tProvider);
    }
  }

  /**
   * Builder for {@link Injector}.
   */
  public static class Builder {
    private final Injector superInjector;
    private final Map<Key<?>, Provider<?>> providers = new HashMap<>();
    private final Map<Key<?>, Class<?>> explicitImpls = new HashMap<>();
    private final Map<Key<?>, Class<?>> defaultImpls = new HashMap<>();

    /** Creates a new builder. */
    public Builder() {
      this(null);
    }

    /** Creates a new builder with a parent injector. */
    public Builder(Injector superInjector) {
      this.superInjector = superInjector;
    }

    /** Registers an instance for the given dependency type. */
    public <T> Builder bind(@Nonnull Class<T> type, @Nonnull T instance) {
      return bind(new Key<>(type), instance);
    }

    /** Registers an instance for the given key. */
    public <T> Builder bind(Key<T> key, @Nonnull T instance) {
      providers.put(key, () -> instance);
      return this;
    }

    /** Registers an implementing class for the given dependency type. */
    public <T> Builder bind(
        @Nonnull Class<T> type, @Nonnull Class<? extends T> implementingClass) {
      explicitImpls.put(new Key<>(type), implementingClass);
      return this;
    }

    /** Registers a fallback implementing class for the given dependency type. */
    public <T> Builder bindDefault(
        @Nonnull Class<T> type, @Nonnull Class<? extends T> defaultClass) {
      defaultImpls.put(new Key<>(type), defaultClass);
      return this;
    }

    /** Builds an injector as previously configured. */
    public Injector build() {
      return new Injector(superInjector, providers, explicitImpls, defaultImpls);
    }
  }

  private <T> Provider<T> registerMemoized(
      @Nonnull Key<T> key, @Nonnull Class<? extends T> defaultClass) {
    return registerMemoized(key, () -> inject(defaultClass));
  }

  private synchronized <T> Provider<T> registerMemoized(
      @Nonnull Key<T> key, Provider<T> tProvider) {
    Provider<T> provider = new MemoizingProvider<>(tProvider);
    providers.put(key, provider);
    return provider;
  }

  @SuppressWarnings("unchecked")
  private <T> T inject(@Nonnull Class<? extends T> clazz) {
    try {
      List<Constructor<T>> injectCtors = new ArrayList<>();
      List<Constructor<T>> otherCtors = new ArrayList<>();

      for (Constructor<?> ctor : clazz.getConstructors()) {
        if (ctor.isAnnotationPresent(Inject.class)) {
          injectCtors.add((Constructor<T>) ctor);
        } else {
          otherCtors.add((Constructor<T>) ctor);
        }
      }

      Constructor<T> ctor;
      if (injectCtors.size() > 1) {
        throw new InjectionException(clazz, "multiple public @Inject constructors");
      } else if (injectCtors.size() == 1) {
        ctor = injectCtors.get(0);
      } else if (otherCtors.size() > 1 && !isSystem(clazz)) {
        throw new InjectionException(clazz, "multiple public constructors");
      } else if (otherCtors.size() == 1) {
        ctor = otherCtors.get(0);
      } else if (isSystem(clazz)) {
        throw new InjectionException(clazz, "nothing provided");
      } else {
        throw new InjectionException(clazz, "no public constructor");
      }

      final Object[] params = new Object[ctor.getParameterCount()];

      AnnotatedType[] paramTypes = ctor.getAnnotatedParameterTypes();
      Annotation[][] parameterAnnotations = ctor.getParameterAnnotations();
      for (int i = 0; i < paramTypes.length; i++) {
        AnnotatedType paramType = paramTypes[i];
        String name = findName(parameterAnnotations[i]);
        Key<?> key = new Key<>(paramType.getType(), name);
        try {
          params[i] = getInstance(key);
        } catch (InjectionException e) {
          throw new InjectionException(clazz,
              "failed to inject " + key + " param", e);
        }
      }

      return ctor.newInstance(params);

    } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
      throw new InjectionException(clazz, e);
    }
  }

  private String findName(Annotation[] annotations) {
    for (Annotation annotation : annotations) {
      if (annotation instanceof Named) {
        return ((Named) annotation).value();
      }
    }
    return null;
  }

  /** Finds a provider for the given key. Calls are guaranteed idempotent. */
  @SuppressWarnings("unchecked")
  private synchronized <T> Provider<T> getProvider(final Key<T> key) {
    Provider<?> provider = providers.computeIfAbsent(key, k -> new Provider<T>() {
      @Override
      public synchronized T get() {
        if (key.isArray()) {
          Provider<T> tProvider = new ArrayProvider(key.getComponentType());
          return registerMemoized(key, tProvider).get();
        }

        if (key.isCollection()) {
          Provider<T> tProvider = new ListProvider(key.getComponentType());
          return registerMemoized(key, tProvider).get();
        }

        Class<T> clazz = (Class) key.theInterface;

        Class<? extends T> implClass = pluginFinder.findPlugin(clazz);

        if (implClass == null) {
          implClass = getDefaultImpl(key);
        }

        if (clazz.isAnnotationPresent(AutoFactory.class)) {
          return registerMemoized(key, new ScopeBuilderProvider<>(clazz)).get();
        }

        if (isConcrete(clazz)) {
          implClass = clazz;
        }

        if (implClass == null && superInjector != null) {
          return superInjector.getInstance(clazz);
        }

        if (implClass == null) {
          throw new InjectionException(clazz, "no provider found");
        }

        // replace this with the found provider for future lookups...
        return registerMemoized(key, implClass).get();
      }

    });
    return (Provider<T>) provider;
  }

  private <T> boolean isConcrete(Class<T> clazz) {
    return !clazz.isInterface() && !Modifier.isAbstract(clazz.getModifiers());
  }

  private <T> Class<? extends T> getDefaultImpl(Key<T> key) {
    Class<?> aClass = defaultImpls.get(key);
    return (Class<? extends T>) aClass;
  }

  /** Finds an instance for the given type. Calls are guaranteed idempotent. */
  public Object getInstance(Type type) {
    return getInstance(new Key<>(type));
  }

  /** Finds an instance for the given class. Calls are guaranteed idempotent. */
  public <T> T getInstance(Class<T> type) {
    return getInstance(new Key<>(type));
  }

  /** Finds an instance for the given key. Calls are guaranteed idempotent. */
  private <T> T getInstance(Key<T> key) {
    Provider<T> provider = getProvider(key);

    if (provider == null) {
      throw new InjectionException(key, "no provider registered");
    }

    return provider.get();
  }

  private boolean isSystem(Class<?> clazz) {
    if (clazz.isPrimitive()) {
      return true;
    }
    Package aPackage = clazz.getPackage();
    return aPackage == null || aPackage.getName().startsWith("java.");
  }

  public static class Key<T> {

    @Nonnull
    private final Type theInterface;
    private final String name;

    private Key(@Nonnull Type theInterface) {
      this(theInterface, null);
    }

    public Key(Type theInterface, String name) {
      this.theInterface = theInterface;
      this.name = name;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Key)) {
        return false;
      }
      Key key = (Key) o;
      return theInterface.equals(key.theInterface) &&
          Objects.equals(name, key.name);
    }

    @Override
    public int hashCode() {
      return Objects.hash(theInterface, name);
    }

    @Override
    public String toString() {
      StringBuilder buf = new StringBuilder();
      buf.append("Key<").append(theInterface);
      if (name != null) {
        buf.append(" named \"")
            .append(name)
            .append("\"");
      }
      buf.append(">");
      return buf.toString();
    }

    public boolean isArray() {
      return theInterface instanceof Class && ((Class) theInterface).isArray();
    }

    public boolean isCollection() {
      if (theInterface instanceof ParameterizedType) {
        Type rawType = ((ParameterizedType) theInterface).getRawType();
        return Collection.class.isAssignableFrom((Class<?>) rawType);
      }
      return false;
    }

    public Class<?> getComponentType() {
      if (isArray()) {
        return ((Class) theInterface).getComponentType();
      } else if (isCollection() && theInterface instanceof ParameterizedType) {
        return (Class) ((ParameterizedType) theInterface).getActualTypeArguments()[0];
      } else {
        throw new IllegalStateException(theInterface + "...?");
      }
    }
  }

  private static class MemoizingProvider<T> implements Provider<T> {

    private Provider<T> delegate;
    private T instance;

    private MemoizingProvider(Provider<T> delegate) {
      this.delegate = delegate;
    }

    @Override
    public synchronized T get() {
      if (instance == null) {
        instance = delegate.get();
        delegate = null;
      }
      return instance;
    }
  }

  private class ListProvider<T> implements Provider<List<T>> {

    private final Class<T> clazz;

    ListProvider(Class<T> clazz) {
      this.clazz = clazz;
    }

    @Override
    public List<T> get() {
      List<T> plugins = new ArrayList<>();
      for (Class<? extends T> pluginClass : pluginFinder.findPlugins(clazz)) {
        plugins.add(inject(pluginClass));
      }
      return Collections.unmodifiableList(plugins);
    }
  }

  private class ArrayProvider<T> implements Provider<T[]> {

    private final ListProvider<T> listProvider;

    ArrayProvider(Class<T> clazz) {
      this.listProvider = new ListProvider<>(clazz);
    }

    @Override
    public T[] get() {
      T[] emptyArray = (T[]) Array.newInstance(listProvider.clazz, 0);
      return listProvider.get().toArray(emptyArray);
    }
  }

  private class ScopeBuilderProvider<T> implements Provider<T> {

    private final Class<T> clazz;

    public ScopeBuilderProvider(Class<T> clazz) {
      this.clazz = clazz;
    }

    @Override
    public T get() {
      return (T) Proxy.newProxyInstance(clazz.getClassLoader(), new Class[]{clazz},
          (proxy, method, args) -> create(method, args));
    }

    private Object create(Method method, Object[] args) {
      Builder subBuilder = new Injector.Builder(Injector.this);
      AnnotatedType[] parameterTypes = method.getAnnotatedParameterTypes();
      Annotation[][] parameterAnnotations = method.getParameterAnnotations();
      for (int i = 0; i < args.length; i++) {
        Type paramType = parameterTypes[i].getType();
        String name = findName(parameterAnnotations[i]);
        Object arg = args[i];
        subBuilder.bind(new Key<>(paramType, name), arg);
      }
      Injector subInjector = subBuilder.build();
      return subInjector.getInstance(method.getReturnType());
    }
  }
}
