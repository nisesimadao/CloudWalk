package dev.cloudwalk

interface TrackRepository {
    fun featured(): List<Track>
    fun search(query: String): List<Track>
}

class DemoTrackRepository : TrackRepository {
    private val items = listOf(
        Track("demo-1", "Night Drive", "CloudWalk Demo"),
        Track("demo-2", "Glass City", "Aster"),
        Track("demo-3", "Orange Line", "Demo Artist"),
        Track("demo-4", "Afterimage", "Nami"),
        Track("demo-5", "Parallel", "Yoru"),
        Track("demo-6", "Signal", "CloudWalk Demo")
    )

    override fun featured(): List<Track> = items

    override fun search(query: String): List<Track> {
        if (query.isBlank()) return items
        return items.filter {
            it.title.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true)
        }
    }
}
