package com.clubs.clubquality

import org.springframework.stereotype.Component

@Component
class ClubStatsMapper {

    fun toDto(stats: ClubStats): ClubStatsDto = ClubStatsDto(
        clubType = stats.clubType.name,
        retentionPercent = stats.retentionPercent,
        retentionTrend = stats.retentionTrend?.let(::toTrendDto),
        churnedThisPeriod = stats.churnedThisPeriod,
        rejoinedThisPeriod = stats.rejoinedThisPeriod,
        engagementPercent = stats.engagementPercent,
        engagementTrend = stats.engagementTrend?.let(::toTrendDto),
        skladchinaPaidPercent = stats.skladchinaPaidPercent,
        skladchinaPaidTrend = stats.skladchinaPaidTrend?.let(::toTrendDto),
        pendingApplications = stats.pendingApplications,
        stalePendingApplications = stats.stalePendingApplications,
        attendanceDisputes = stats.attendanceDisputes,
        totalMeetings = stats.totalMeetings,
        autoRejectedApplications = stats.autoRejectedApplications,
        cancelledMeetings = stats.cancelledMeetings,
    )

    private fun toTrendDto(trend: Trend): TrendDto = TrendDto(trend.direction.name, trend.delta)

    fun toChurnedDto(member: ChurnedMember): ChurnedMemberDto = ChurnedMemberDto(
        userId = member.userId,
        firstName = member.firstName,
        lastName = member.lastName,
        avatarUrl = member.avatarUrl,
        leftAt = member.leftAt,
    )
}
