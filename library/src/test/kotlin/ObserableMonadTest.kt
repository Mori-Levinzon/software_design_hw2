import org.junit.jupiter.api.Test
import il.ac.technion.cs.softwaredesign.ObservableMonad
import org.junit.jupiter.api.assertThrows
import java.lang.IllegalStateException

internal class ObservableMonadTest {
    private fun f(x: Long): ObservableMonad<Long> = ObservableMonad.of(x * x)
    private fun g(x: Long): ObservableMonad<Long> = ObservableMonad.of(x * x * x)
    /**
     * Law 1: Left identity
     * The first monad law states that if we take a value, put it in a default context with return and
     * then feed it to a function by using >>=, it's the same as just taking the value and applying
     * the function to it. To put it formally:
     *
     *  return x >>= f is the same damn thing as f x
     *
     *  http://learnyouahaskell.com/a-fistful-of-monads#monad-laws
     */
    @Test
    fun leftIdentity() {
        val x = 5L
        val lhs = ObservableMonad.of<Long>(x).flatMap { x -> f(x) }
        val rhs = f(x)
        assert(lhs == rhs)
    }
    /**
     *  Law 2: Right Identity
     *  The second law states that if we have a monadic value and we use >>= to feed it to return,
     *  the result is our original monadic value. Formally:
     *
     *  m >>= return is no different than just m
     *
     *  http://learnyouahaskell.com/a-fistful-of-monads#monad-laws
     */
    @Test
    fun rightIdentity() {
        val monadValue = ObservableMonad.of<Long>(5)
        val rhs = monadValue.flatMap { value -> ObservableMonad.of<Long>(value) }
        assert(monadValue == rhs)
    }
    /**
     * Law 3: Associativity *
     * The final monad law says that when we have a chain of monadic function applications with >>=,
     * it shouldn't matter how they're nested. Formally written:
     * Doing (m >>= f) >>= g is just like doing m >>= (\x -> f x >>= g) *
     *
     * http://learnyouahaskell.com/a-fistful-of-monads#monad-laws
     */
    @Test
    fun associativity() {
        val monad = ObservableMonad.of<Long>(5)
        val lhs = monad.flatMap { m -> f(m) }.flatMap { m -> g(m) }
        val rhs = monad.flatMap { f(5).flatMap { x -> g(x) } }
        assert(lhs == rhs)
    }
}