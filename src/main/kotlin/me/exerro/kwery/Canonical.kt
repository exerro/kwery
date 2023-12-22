package me.exerro.kwery

/**
 * Annotates query handlers which may be used without explicitly registering
 * them with a [QueryEngine].
 *
 * Must still be registered using [QueryEngine.Builder.addCanonicalQueryHandler]
 * or [QueryEngine.Builder.addCanonicalQueryHandlers].
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Canonical
