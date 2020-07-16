package il.ac.technion.cs.softwaredesign
class ObservableMonad<T> constructor(private val value: T) {
    private val observers = mutableListOf<(T) -> Any >()
    companion object {
        @JvmStatic
        fun <T> of(value: T): ObservableMonad<T> = ObservableMonad(value)
    }

    fun <S> flatMap(functor: (T) -> ObservableMonad<S>): ObservableMonad<S> = functor(value)

    fun addObserver(callback: (T) -> Any): ObservableMonad<T> {
        observers.add(callback)
        return this
    }
    fun removeObserver(callback: (T) -> Any): ObservableMonad<T> {
        observers.remove(callback)
        return this
    }
    fun notify(x: T): Unit {
        observers.forEach { observer -> observer(x) }
    }
    override fun equals(other: Any?): Boolean {
        if (this === other)
            return true
        if (other !is ObservableMonad<*>)
            return false
        if (value != other.value)
            return false
        return true
    }
    override fun hashCode(): Int {
        var result = value?.hashCode() ?: 0
        result = 31 * result + observers.hashCode()
        return result
    }
}
