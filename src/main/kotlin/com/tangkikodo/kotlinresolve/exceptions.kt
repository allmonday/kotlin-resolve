package com.tangkikodo.kotlinresolve

class ResolverTargetAttrNotFound(message: String) : RuntimeException(message)
class MissingAnnotationError(message: String) : RuntimeException(message)
class LoaderFieldNotProvidedError(message: String) : RuntimeException(message)
class MissingCollector(message: String) : RuntimeException(message)
class LoaderContextNotProvidedError(message: String) : RuntimeException(message)
class GlobalLoaderFieldOverlappedError(message: String) : RuntimeException(message)
