/*******************************************************************************
 * Copyright (c) 2008, 2014 Stuart McCulloch
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Stuart McCulloch - initial API and implementation
 *******************************************************************************/

package org.eclipse.sisu.peaberry.util.decorators;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.eclipse.sisu.peaberry.Import;
import org.eclipse.sisu.peaberry.ServiceUnavailableException;
import org.eclipse.sisu.peaberry.builders.ImportDecorator;
import org.eclipse.sisu.peaberry.util.DelegatingImport;

import com.google.inject.matcher.Matcher;

/**
 * An {@link ImportDecorator} that supports {@link MethodInterceptor}s.
 * 
 * @author mcculls@gmail.com (Stuart McCulloch)
 */
public final class InterceptingDecorator<S>
    implements ImportDecorator<S> {

  final Matcher<? super Class<?>> classMatcher;
  final Matcher<? super Method> methodMatcher;
  final MethodInterceptor[] interceptors;

  public InterceptingDecorator(final Matcher<? super Class<?>> classMatcher,
      final Matcher<? super Method> methodMatcher, final MethodInterceptor... interceptors) {

    this.classMatcher = classMatcher;
    this.methodMatcher = methodMatcher;
    this.interceptors = interceptors;

    if (interceptors.length == 0) {
      throw new IllegalArgumentException("Must provide at least one method interceptor");
    }
  }

  // use JDK proxy for simplicity
  private final class ProxyImport<T>
      extends DelegatingImport<T>
      implements InvocationHandler {

    private volatile T proxy;

    ProxyImport(final Import<T> service) {
      super(service);
    }

    @Override
    @SuppressWarnings("unchecked")
    public T get() {
      if (null == proxy) {
        synchronized (this) {
          try {
            final T instance = super.get();
            if (null == proxy && null != instance) {
              // lazily-create proxy, only needs to be created once per service
              final ClassLoader loader = interceptors[0].getClass().getClassLoader();
              proxy = (T) Proxy.newProxyInstance(loader, getInterfaces(instance), this);
            }
          } finally {
            super.unget();
          }
        }
      }
      return proxy; // proxy will use get() to delegate to the active service
    }

    @Override
    public void unget() {/* proxy does the cleanup */}

    public Object invoke(final Object unused, final Method method, final Object[] args)
        throws Throwable {
      try {

        final Object instance = super.get();
        if (null == instance) {
          throw new ServiceUnavailableException();
        }

        // only intercept interesting methods
        if (!methodMatcher.matches(method) || !classMatcher.matches(method.getDeclaringClass())) {
          return method.invoke(instance, args);
        }

        return intercept(instance, method, args);

      } finally {
        super.unget();
      }
    }

    private Object intercept(final Object instance, final Method method, final Object[] args)
        throws Throwable {

      // begin chain of intercepting method invocations
      return interceptors[0].invoke(new MethodInvocation() {
        private int index = 1;

        public AccessibleObject getStaticPart() {
          return method;
        }

        public Method getMethod() {
          return method;
        }

        public Object[] getArguments() {
          return args;
        }

        public Object getThis() {
          return instance;
        }

        public Object proceed()
            throws Throwable {
          try {
            // walk down the stack of interceptors
            if (index < interceptors.length) {
              return interceptors[index++].invoke(this);
            }
            // no more interceptors, invoke service
            return method.invoke(instance, args);
          } finally {
            index--; // rollback in case called again
          }
        }
      });
    }
  }

  static Class<?>[] getInterfaces(final Object instance) {
    @SuppressWarnings("unchecked")
    final Set<Class> api = new HashSet<Class>();
    // look through the entire class hierarchy collecting all visible interfaces
    for (Class<?> clazz = instance.getClass(); null != clazz; clazz = clazz.getSuperclass()) {
      Collections.addAll(api, clazz.getInterfaces());
    }
    return api.toArray(new Class[api.size()]);
  }

  public <T extends S> Import<T> decorate(final Import<T> service) {
    return new ProxyImport<T>(service);
  }
}
