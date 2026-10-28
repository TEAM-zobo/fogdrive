package com.zobo.burung

import com.zobo.burung.car.VehicleState
import com.zobo.burung.explore.CrewMission
import com.zobo.burung.explore.DetourPlanner
import com.zobo.burung.explore.LocalStories
import com.zobo.burung.explore.StoryCandidate
import com.zobo.burung.record.RecordingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeaturesTest {
    @Test
    fun crewMissionProgressPerKind() {
        val dongs = CrewMission(CrewMission.Kind.NEW_DONGS, 2)
        assertEquals(1, dongs.progress(newDongs = 1, areaM2 = 5_000.0, newRoadMeters = 900.0))
        assertEquals("2/2", dongs.progressLabel(5))  // capped at the target
        val area = CrewMission(CrewMission.Kind.AREA, 100_000)
        assertEquals(42_000, area.progress(0, 42_000.4, 0.0))
        val road = CrewMission(CrewMission.Kind.NEW_ROAD_KM, 3)
        assertEquals(2, road.progress(0, 0.0, 2_999.0))
        assertEquals("새 길 3 km 달리기", road.title())
    }

    @Test
    fun crewMissionPickIsStableWithinAMinute() {
        val t = 1_761_350_400_000L
        assertEquals(CrewMission.pick(t), CrewMission.pick(t + 59_000))
        assertEquals(3, (0 until 3).map { CrewMission.pick(t + it * 60_000L).kind }.toSet().size)
    }

    @Test
    fun passengerMeansAnyoneButTheDriver() {
        assertFalse(VehicleState(occupiedSeats = setOf("ROW_1_LEFT")).hasPassenger)
        assertTrue(VehicleState(occupiedSeats = setOf("ROW_1_LEFT", "ROW_1_RIGHT")).hasPassenger)
        assertTrue(VehicleState(occupiedSeats = setOf("ROW_2_LEFT")).hasPassenger)
    }

    @Test
    fun detourNeedsRangeForTheWayBack() {
        // 20 km away, 3 km detour: (40 + 3) x 1.3 + 30 reserve ≈ 86 km.
        assertTrue(DetourPlanner.fitsRange(100.0, 20_000.0, 3_000.0))
        assertFalse(DetourPlanner.fitsRange(80.0, 20_000.0, 3_000.0))
    }

    @Test
    fun storyPicksNearestLandmarkOverAdministrativePages() {
        val story = LocalStories.pick(
            listOf(
                StoryCandidate("성수동1가", "서울특별시 성동구의 법정동", null, 0),
                StoryCandidate("뚝섬역", "서울 지하철 2호선의 역", null, 1),
                StoryCandidate("서울숲", null, "서울숲은 서울특별시 성동구에 있는 공원이다. 2005년에 개장했다.", 2),
                StoryCandidate("언더스탠드에비뉴", "복합문화공간", null, 3),
            ),
        )
        assertEquals("서울숲", story?.title)
        assertEquals("서울숲은 서울특별시 성동구에 있는 공원이다.", story?.line)
        assertNull(LocalStories.pick(listOf(StoryCandidate("성수동", "행정동", null, 0))))
    }

    @Test
    fun storyLinesAreTrimmed() {
        val long = "가".repeat(100)
        assertEquals(60, LocalStories.pick(listOf(StoryCandidate("어떤 명소", long, null, 0)))!!.line.length)
    }

    @Test
    fun spokenAreaReadsNaturally() {
        assertEquals("0.12제곱킬로미터", RecordingService.spokenArea(120_000.0))
        assertEquals("1제곱킬로미터", RecordingService.spokenArea(1_000_000.0))
        assertEquals("4500제곱미터", RecordingService.spokenArea(4_560.0))
    }

    @Test
    fun copulaFollowsTheLastSyllable() {
        assertEquals("이에요", RecordingService.copula("성수1가1동"))
        assertEquals("예요", RecordingService.copula("종로1가"))
        assertEquals("이에요", RecordingService.copula("A1"))
    }
}
