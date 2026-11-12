package com.gpstelemetry.app

import com.gpstelemetry.core.LeaderboardEntry
import com.gpstelemetry.core.PublishedTrack
import com.gpstelemetry.core.Session
import com.gpstelemetry.core.Track

/** Repository finto per vedere in anteprima la scheda Esplora con dati. */
class FakeExploreRepository(private val sample: Track) : ExploreRepository {
    override val isAvailable = true

    private val board = listOf(
        LeaderboardEntry(1, "Marta R.", 44_120, listOf(14_100, 14_420, 15_600), 0),
        LeaderboardEntry(2, "Tu", 45_800, listOf(15_000, 15_200, 15_600), 0, isMe = true),
        LeaderboardEntry(3, "Luca B.", 46_310, listOf(15_300, 15_210, 15_800), 0),
    )

    override fun listTracks(
        query: String?,
        nearLat: Double?,
        nearLon: Double?,
        callback: (Result<List<PublishedTrack>>) -> Unit,
    ) = callback(
        Result.success(
            listOf(
                PublishedTrack("p1", sample.copy(name = "Kartodromo del Lago"), "Marta R.", 0, 128, board, 3_400.0),
                PublishedTrack("p2", sample.copy(name = "Anello Colline"), "Giorgio P.", 0, 1, board.take(1), 12_800.0),
            )
        )
    )

    override fun leaderboard(publishedId: String, callback: (Result<List<LeaderboardEntry>>) -> Unit) =
        callback(Result.success(board))

    override fun publishTrack(track: Track, callback: (Result<PublishedTrack>) -> Unit) =
        callback(Result.failure(UnsupportedOperationException()))

    override fun submitLap(publishedId: String, session: Session, callback: (Result<LeaderboardEntry>) -> Unit) =
        callback(Result.failure(UnsupportedOperationException()))
}
