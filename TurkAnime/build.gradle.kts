version = 2

cloudstream {
    authors     = listOf("Jung1330", "StreamTest", "AI-Assistant")
    language    = "tr"
    description = "StreamTest - TurkAnime Eklentisi (AI Destekli Olarak Güncellenmiştir)."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("Anime")
    iconUrl = "https://www.google.com/s2/favicons?domain=www.turkanime.co&sz=%size%"
}
