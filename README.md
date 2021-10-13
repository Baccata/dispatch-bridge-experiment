## Dispatch bridge

Attempt to provide something on top of Dispatcher that would help with communicating algebraic information through unsafe regions.

Most of the implementation is derived from [cats-effect-cps](https://github.com/typelevel/cats-effect-cps)

The main problem is that the two "shores" of an unsafe regions are hard to link together logically, in a generic fashion.  so this implementation is using a Queue of size 1 to logically bridge the two shores. This obviously crumbles as soon as the soviet-interop needs to call `unsafeRun` more than once.

It might be  
