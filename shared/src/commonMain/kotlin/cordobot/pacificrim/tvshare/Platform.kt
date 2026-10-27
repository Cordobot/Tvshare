package cordobot.pacificrim.tvshare

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform