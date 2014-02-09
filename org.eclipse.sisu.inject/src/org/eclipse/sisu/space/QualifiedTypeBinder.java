/*******************************************************************************
 * Copyright (c) 2010, 2013 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Stuart McCulloch (Sonatype, Inc.) - initial API and implementation
 *******************************************************************************/
package org.eclipse.sisu.space;

import java.lang.annotation.Annotation;
import java.lang.annotation.IncompleteAnnotationException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import javax.inject.Provider;

import org.eclipse.sisu.EagerSingleton;
import org.eclipse.sisu.Mediator;
import org.eclipse.sisu.inject.BeanLocator;
import org.eclipse.sisu.inject.TypeArguments;

import com.google.inject.Binder;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.Scopes;
import com.google.inject.TypeLiteral;
import com.google.inject.binder.ScopedBindingBuilder;
import com.google.inject.matcher.Matchers;
import com.google.inject.name.Named;
import com.google.inject.name.Names;

/**
 * {@link QualifiedTypeListener} that installs {@link Module}s, registers {@link Mediator}s, and binds types.
 */
@SuppressWarnings( { "unchecked", "rawtypes" } )
public final class QualifiedTypeBinder
    implements QualifiedTypeListener
{
    // ----------------------------------------------------------------------
    // Static initialization
    // ----------------------------------------------------------------------

    static
    {
        boolean hasTyped;
        try
        {
            hasTyped = javax.enterprise.inject.Typed.class.isAnnotation();
        }
        catch ( final LinkageError e )
        {
            hasTyped = false;
        }
        HAS_TYPED = hasTyped;
    }

    // ----------------------------------------------------------------------
    // Constants
    // ----------------------------------------------------------------------

    private static final TypeLiteral<?> OBJECT_TYPE_LITERAL = TypeLiteral.get( Object.class );

    private static final boolean HAS_TYPED;

    // ----------------------------------------------------------------------
    // Implementation fields
    // ----------------------------------------------------------------------

    private final Binder rootBinder;

    private MediationListener mediationListener;

    private Object currentSource;

    private Binder binder;

    // ----------------------------------------------------------------------
    // Constructors
    // ----------------------------------------------------------------------

    public QualifiedTypeBinder( final Binder binder )
    {
        rootBinder = binder;
        this.binder = binder;
    }

    // ----------------------------------------------------------------------
    // Public methods
    // ----------------------------------------------------------------------

    @SuppressWarnings( "deprecation" )
    public void hear( final Class qualifiedType, final Object source )
    {
        if ( currentSource != source )
        {
            if ( null != source )
            {
                binder = rootBinder.withSource( source );
                currentSource = source;
            }
            else
            {
                binder = rootBinder;
                currentSource = null;
            }
        }

        if ( !TypeArguments.isConcrete( qualifiedType ) )
        {
            return;
        }
        else if ( Module.class.isAssignableFrom( qualifiedType ) )
        {
            installModule( qualifiedType );
        }
        else if ( Mediator.class.isAssignableFrom( qualifiedType ) )
        {
            registerMediator( qualifiedType );
        }
        else if ( org.sonatype.inject.Mediator.class.isAssignableFrom( qualifiedType ) )
        {
            registerLegacyMediator( qualifiedType );
        }
        else if ( Provider.class.isAssignableFrom( qualifiedType ) )
        {
            bindProviderType( qualifiedType );
        }
        else
        {
            bindQualifiedType( qualifiedType );
        }
    }

    // ----------------------------------------------------------------------
    // Implementation methods
    // ----------------------------------------------------------------------

    /**
     * Installs an instance of the given {@link Module}.
     * 
     * @param moduleType The module type
     */
    private void installModule( final Class<Module> moduleType )
    {
        final Module module = newInstance( moduleType );
        if ( null != module )
        {
            binder.install( module );
        }
    }

    /**
     * Registers an instance of the given {@link Mediator} using its generic type arguments as configuration.
     * 
     * @param mediatorType The mediator type
     */
    private void registerMediator( final Class<Mediator> mediatorType )
    {
        final TypeLiteral<?>[] args = resolveTypeArguments( mediatorType, Mediator.class );
        if ( args.length != 3 )
        {
            binder.addError( mediatorType + " has wrong number of type arguments" );
        }
        else
        {
            final Mediator mediator = newInstance( mediatorType );
            if ( null != mediator )
            {
                mediate( watchedKey( args[1], (Class) args[0].getRawType() ), mediator, args[2].getRawType() );
            }
        }
    }

    @SuppressWarnings( "deprecation" )
    private void registerLegacyMediator( final Class<org.sonatype.inject.Mediator> mediatorType )
    {
        final TypeLiteral<?>[] args = resolveTypeArguments( mediatorType, org.sonatype.inject.Mediator.class );
        if ( args.length != 3 )
        {
            binder.addError( mediatorType + " has wrong number of type arguments" );
        }
        else
        {
            final Mediator mediator = org.eclipse.sisu.inject.Legacy.adapt( newInstance( mediatorType ) );
            if ( null != mediator )
            {
                mediate( watchedKey( args[1], (Class) args[0].getRawType() ), mediator, args[2].getRawType() );
            }
        }
    }

    /**
     * Uses the given mediator to mediate updates between the {@link BeanLocator} and associated watchers.
     * 
     * @param watchedKey The watched key
     * @param mediator The bean mediator
     * @param watcherType The watcher type
     */
    private void mediate( final Key watchedKey, final Mediator mediator, final Class watcherType )
    {
        if ( null == mediationListener )
        {
            mediationListener = new MediationListener( binder );
            binder.bindListener( Matchers.any(), mediationListener );
        }
        mediationListener.mediate( watchedKey, mediator, watcherType );
    }

    /**
     * Binds the given provider type using a binding key determined by common-use heuristics.
     * 
     * @param providerType The provider type
     */
    private void bindProviderType( final Class<?> providerType )
    {
        final TypeLiteral[] args = resolveTypeArguments( providerType, javax.inject.Provider.class );
        if ( args.length != 1 )
        {
            binder.addError( providerType + " has wrong number of type arguments" );
        }
        else
        {
            binder.bind( providerType ).in( Scopes.SINGLETON );

            final Named bindingName = getBindingName( providerType );
            final Class<?>[] types = getBindingTypes( providerType );

            final Key key = getBindingKey( args[0], bindingName );
            final ScopedBindingBuilder sbb = binder.bind( key ).toProvider( providerType );
            if ( isEagerSingleton( providerType ) )
            {
                sbb.asEagerSingleton();
            }
            else if ( isSingleton( providerType ) )
            {
                sbb.in( Scopes.SINGLETON );
            }

            if ( null != types )
            {
                for ( final Class bindingType : types )
                {
                    binder.bind( key.ofType( bindingType ) ).to( key );
                }
            }
        }
    }

    /**
     * Binds the given qualified type using a binding key determined by common-use heuristics.
     * 
     * @param qualifiedType The qualified type
     */
    private void bindQualifiedType( final Class<?> qualifiedType )
    {
        final ScopedBindingBuilder sbb = binder.bind( qualifiedType );
        if ( isEagerSingleton( qualifiedType ) )
        {
            sbb.asEagerSingleton();
        }

        final Named bindingName = getBindingName( qualifiedType );
        final Class<?>[] types = getBindingTypes( qualifiedType );

        if ( null != types )
        {
            final Key key = getBindingKey( OBJECT_TYPE_LITERAL, bindingName );
            for ( final Class bindingType : types )
            {
                binder.bind( key.ofType( bindingType ) ).to( qualifiedType );
            }
        }
        else
        {
            binder.bind( new WildcardKey( qualifiedType, bindingName ) ).to( qualifiedType );
        }
    }

    /**
     * Attempts to create a new instance of the given type.
     * 
     * @param type The instance type
     * @return New instance; {@code null} if the instance couldn't be created
     */
    private <T> T newInstance( final Class<T> type )
    {
        try
        {
            // slightly roundabout approach, but it might be private
            final Constructor<T> ctor = type.getDeclaredConstructor();
            ctor.setAccessible( true );
            return ctor.newInstance();
        }
        catch ( final Exception e )
        {
            final Throwable cause = e instanceof InvocationTargetException ? e.getCause() : e;
            binder.addError( "Error creating instance of: " + type + " reason: " + cause );
            return null;
        }
        catch ( final LinkageError e )
        {
            binder.addError( "Error creating instance of: " + type + " reason: " + e );
            return null;
        }
    }

    /**
     * Resolves the type arguments of a super type based on the given concrete type.
     * 
     * @param type The concrete type
     * @param superType The generic super type
     * @return Resolved super type arguments
     */
    private static TypeLiteral<?>[] resolveTypeArguments( final Class<?> type, final Class<?> superType )
    {
        return TypeArguments.get( TypeLiteral.get( type ).getSupertype( superType ) );
    }

    private static <T> Key<T> getBindingKey( final TypeLiteral<T> bindingType, final Annotation qualifier )
    {
        return null != qualifier ? Key.get( bindingType, qualifier ) : Key.get( bindingType );
    }

    private static Named getBindingName( final Class<?> qualifiedType )
    {
        final javax.inject.Named jsr330 = qualifiedType.getAnnotation( javax.inject.Named.class );
        if ( null != jsr330 )
        {
            try
            {
                final String name = jsr330.value();
                if ( name.length() > 0 )
                {
                    return "default".equals( name ) ? null : Names.named( name );
                }
            }
            catch ( final IncompleteAnnotationException e ) // NOPMD
            {
                // early prototypes of JSR330 @Named declared no default value
            }
        }
        else
        {
            final Named guice = qualifiedType.getAnnotation( Named.class );
            if ( null != guice )
            {
                final String name = guice.value();
                if ( name.length() > 0 )
                {
                    return "default".equals( name ) ? null : guice;
                }
            }
        }

        if ( qualifiedType.getSimpleName().startsWith( "Default" ) )
        {
            return null;
        }

        return Names.named( qualifiedType.getName() );
    }

    private static Class<?>[] getBindingTypes( final Class<?> clazz )
    {
        if ( HAS_TYPED )
        {
            for ( Class<?> c = clazz; c != Object.class; c = c.getSuperclass() )
            {
                final javax.enterprise.inject.Typed typed = c.getAnnotation( javax.enterprise.inject.Typed.class );
                if ( null != typed )
                {
                    return typed.value().length > 0 ? typed.value() : c.getInterfaces();
                }
            }
        }
        return null;
    }

    private static boolean isSingleton( final Class<?> type )
    {
        return type.isAnnotationPresent( javax.inject.Singleton.class )
            || type.isAnnotationPresent( com.google.inject.Singleton.class );
    }

    @SuppressWarnings( "deprecation" )
    private static boolean isEagerSingleton( final Class<?> type )
    {
        return type.isAnnotationPresent( EagerSingleton.class )
            || type.isAnnotationPresent( org.sonatype.inject.EagerSingleton.class );
    }

    private static <T> Key<T> watchedKey( final TypeLiteral<T> type, final Class<? extends Annotation> annotationType )
    {
        return Annotation.class != annotationType ? Key.get( type, annotationType ) : Key.get( type );
    }
}
