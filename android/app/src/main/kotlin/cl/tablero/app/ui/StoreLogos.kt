package cl.tablero.app.ui

/*
 * Each store's own logo, found on its home page (the header image, else its
 * icon). Hot-linked, not bundled: a store that changes its logo only costs a
 * fallback initial until this list is refreshed. Raster only -- the app has no
 * SVG decoder. Stores missing here show their initial: calabozotienda, playkingdom, araucania.
 */

/** `dark`: a white or pale logo on a transparent background, drawn on black. */
class StoreLogo(val url: String, val dark: Boolean = false)

val STORE_LOGOS: Map<String, StoreLogo> = mapOf(
    "flexo" to StoreLogo("https://www.flexogames.cl/cdn/shop/files/logo_flexo_games_definitivo_1000_Mesa_de_trabajo_1_2_360x.png?v=1737674074"),
    "lafortalezapuq" to StoreLogo("https://images.jumpseller.com/store/la-fortaleza-punta-arenas1/store/logo/la-fortaleza-web.gif?0", dark = true),
    "planetaloz" to StoreLogo("https://www.planetaloz.cl/img/logo-1789068329.jpg"),
    "updown" to StoreLogo("https://www.updown.cl/wp-content/uploads/2023/09/UpDownJuegos_Logotipo-15-scaled.png"),
    "aldeajuegos" to StoreLogo("https://www.aldeajuegos.cl/img/logo-1734916990.jpg"),
    "elpatiogeek" to StoreLogo("https://www.elpatiogeek.cl/cdn/shop/files/El-Patio-Geek_Logo_SIN-SOMBRA_AMA_180x.png?v=1643297314"),
    "mangaigames" to StoreLogo("https://mangaigames.cl/wp-content/uploads/2024/12/logo-color-300x189-1-150x95.png"),
    "cartonespesados" to StoreLogo("https://cartonespesados.cl/wp-content/uploads/2025/02/Screenshot-2025-02-16-at-5.26.45%E2%80%AFPM.png"),
    "cartonazo" to StoreLogo("https://cartonazo.com/wp-content/uploads/2025/01/logo-cartonazo.png"),
    "dementegames" to StoreLogo("https://dementegames.cl/img/demente-games-logo-1607922075.jpg"),
    "drjuegos" to StoreLogo("https://www.drjuegos.cl/img/logo-1674682948.jpg"),
    "vudugaming" to StoreLogo("https://images.jumpseller.com/store/vudu-gaming/store/logo/Logo_20Vudu_20reducido.png?1750840270"),
    "piedrabruja" to StoreLogo("https://piedrabruja.cl/cdn/shop/files/logo_pb.png?v=1722980507&width=3000"),
    "gatoarcano" to StoreLogo("https://gatoarcano.cl/wp-content/uploads/2024/09/cropped-LOGO-WEB-2024.png.webp"),
    "ludipuerto" to StoreLogo("https://www.ludipuerto.cl/wp-content/uploads/2024/06/logo-para-celu.webp"),
    "magicsur" to StoreLogo("https://www.magicsur.cl/img/magicsur-chile-logo-1539634441.jpg"),
    "gameofmagictienda" to StoreLogo("https://dojiw2m9tvv09.cloudfront.net/55673/3/M_logob5675.png?148&t=1793197358"),
    "top8" to StoreLogo("https://dojiw2m9tvv09.cloudfront.net/68889/2/S_top8-general4500.png?188&t=1793197363"),
    "revaruk" to StoreLogo("https://revaruk.cl/wp-content/uploads/2024/08/FINAL-BLANCO-letras-PNG-03-600x223-1.png", dark = true),
    "labovedadelmago" to StoreLogo("https://www.labovedadelmago.cl/wp-content/uploads/2024/06/LOGO-2_160x136.png"),
    "zonaxgamers" to StoreLogo("https://images.jumpseller.com/store/zona-x-gamers/store/logo/Logo-Web.png?1694836598"),
    "cafe2d6" to StoreLogo("https://www.cafe2d6.cl/cdn/shop/files/2000x2000_Cafe_2D6_color_600x.png?v=1669820588"),
    "griffingames" to StoreLogo("https://www.griffingames.cl/wp-content/uploads/2023/09/logo-web-2025-verde.jpg"),
    "playcenter" to StoreLogo("https://playcenter.cl/wp-content/uploads/2023/12/Logo-SF-1-326x130.png"),
    "enroque" to StoreLogo("https://juegosenroque.cl/cdn/shop/files/logo-enroque-1.png?v=1700441978&width=300", dark = true),
    "kaiojuegos" to StoreLogo("https://kaiojuegos.cl/img/logo-1763231464.jpg"),
    "manahouse" to StoreLogo("https://manahouse.cl/cdn/shop/files/mana-house-favicon-tienda-de-juegos-de-mesa_128x128.png?v=1753837508"),
    "devir" to StoreLogo("https://devir.cl/static/version1785422225/frontend/Devir/Pyre/es_ES/images/logo.png", dark = true),
    "thirdimpact_asmodee" to StoreLogo("https://dojiw2m9tvv09.cloudfront.net/104963/1/logo5201.png?83&t=1793197580"),
    "thirdimpact_devir" to StoreLogo("https://dojiw2m9tvv09.cloudfront.net/104963/1/logo5201.png?83&t=1793197585"),
    "buho" to StoreLogo("https://buhojuegosdemesa.cl/cdn/shop/files/LOGO_OPTIMIZADO_WEB-08_200x.png?v=1614283720", dark = true),
    "mirzu" to StoreLogo("https://mirzu.cl/wp-content/uploads/2022/12/cropped-Logo-a-color-sin-fondo-png-1-scaled-222x90.png"),
    "peakgames" to StoreLogo("https://dojiw2m9tvv09.cloudfront.net/83477/1/S_peakgamespngfondoblancobiensinsubti22170.png?138&t=1793197660"),
    "laloseta" to StoreLogo("https://laloseta.cl/wp-content/uploads/2026/08/La-loseta-logo-remake.webp", dark = true),
    "lamadriguera" to StoreLogo("https://tiendalamadriguera.cl/favicon.ico"),
    "lamesadevaras" to StoreLogo("https://lamesadevaras.cl/img/logo-1671057786.jpg"),
    "wargaming" to StoreLogo("https://dojiw2m9tvv09.cloudfront.net/16153/6/M_logoblanco.png?75&t=1793197669", dark = true),
    "laguaridadeldragon" to StoreLogo("https://dojiw2m9tvv09.cloudfront.net/106891/1/S_logo-removebg-preview3551.png?114&t=1793197674"),
    "chileboardgames" to StoreLogo("https://chileboardgames.com/cdn/shop/files/Chile_Board_Games_-04_FULL_COLOR_HORIZONTAL_4467a9d5-c5ca-4cbe-aefb-0063a8269ad9_360x.png?v=1717726264"),
    "boardkingdom" to StoreLogo("https://assets.jumpseller.com/store/boardkingdom/themes/969043/settings/518a24258aa4a4be711d/logo2.png?1777680913"),
    "darkhobbies" to StoreLogo("https://www.darkhobbies.cl/cdn/shop/files/4_ef256ebd-dd44-4ec8-9d5b-5553dd74af3e.png?height=100&v=1780499659", dark = true),
    "shivano" to StoreLogo("https://shivano.cl/img/shivano-logo-1589478872.jpg"),
    "guildreams" to StoreLogo("https://dojiw2m9tvv09.cloudfront.net/18085/3/M_logo328063155656.png?105&t=1793197806"),
    "jugones" to StoreLogo("https://www.jugones.cl/img/icons/favicon.png"),
    "tertulia" to StoreLogo("https://tertulia.cl/wp-content/uploads/2024/02/Logo-Tertulia.png"),
    "lautarojuegos" to StoreLogo("https://images.jumpseller.com/store/lautaro-juegos/store/logo/logo_nuevo_lautaro_juegos_1.1.png?0"),
    "tentami" to StoreLogo("https://tentami.cl/cdn/shop/files/Recurso_53logo.png?height=100&v=1785634103"),
    "area52" to StoreLogo("https://area52.cl/cdn/shop/files/logo.png?v=1704750116&width=160"),
    "ovniplay" to StoreLogo("https://ovniplay.cl/cdn/shop/files/Diseno_sin_titulo.png?v=1770173549&width=180"),
    "juegosgori" to StoreLogo("https://juegosgori.cl/cdn/shop/files/LOGO_GORI.png?v=1791232889&width=240"),
    "geekz" to StoreLogo("https://geekz.cl/web/image/website/2/logo?unique=9864b71"),
    "ludoteka" to StoreLogo("https://www.ludoteka.cl/img/logo.png?v=20260215"),
    "winterland" to StoreLogo("https://dojiw2m9tvv09.cloudfront.net/72959/1/S_chatgptimage23jul2026-12-57-424508.webp?71&t=1793198075"),
    "diverti" to StoreLogo("https://diverti.cl/img/logo_mobile_color%20blanco.png", dark = true),
    "fillerplace" to StoreLogo("https://fillerplace.cl/wp-content/uploads/2021/12/fillerplace_logo_final-02.png"),
    "spells" to StoreLogo("https://spells.cl/wp-content/uploads/2025/01/png-spells-305x87.png", dark = true),
    "ludipacha" to StoreLogo("https://ludipacha.cl/wp-content/uploads/2024/06/cropped-a3137ef5-4229-442d-90de-59943ee2fdad.jpeg"),
    "mundoimaginario" to StoreLogo("https://mundoimaginario.cl/wp-content/uploads/2024/11/tcg_20241107_092536_0000-e1783822799934.png"),
    "ruta12club" to StoreLogo("https://images.jumpseller.com/store/ruta-12/store/logo/LogoFinal.png?1737317537"),
    "ludopia" to StoreLogo("https://images.jumpseller.com/store/ludopia/store/logo/Sin-Eslogan.png?1700530468"),
)
