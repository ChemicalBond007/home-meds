# Ported from firebase-components:19.0.0 consumer rules. ML Kit currently resolves
# firebase-components:16.1.0, whose rule keeps class names but not constructors.
# These registrars are instantiated through manifest metadata and reflection.
# Upstream: https://github.com/googlesamples/mlkit/issues/1018
-keep class * implements com.google.firebase.components.ComponentRegistrar { void <init>(); }
-keep,allowshrinking interface com.google.firebase.components.ComponentRegistrar
