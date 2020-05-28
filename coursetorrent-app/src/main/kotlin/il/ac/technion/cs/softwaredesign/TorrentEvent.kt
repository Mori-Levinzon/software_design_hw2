package il.ac.technion.cs.softwaredesign

enum class TorrentEvent(val asString : String) {
    STARTED("started"),
    STOPPED("stopped"),
    COMPLETED("completed"),
    REGULAR("") // i.e., announce at a regular interval
}