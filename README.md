# Trident Client para Android

Launcher de **Minecraft: Java Edition** para Android con **Trident Client** incluido.

- Trident Client se instala solo al jugar cualquier versión con **Fabric**, junto con Fabric API, Mod Menu, Lithium, FerriteCore y Entity Culling.
- Pestaña **Trident**: Trident Coins, tienda y equipamiento de cosméticos, amigos y jugadores en línea, sincronizados con el launcher de PC y con el mod.
- Cuentas premium (Microsoft) y no premium (con contraseña de Trident Net).

## Basado en Zalith Launcher 2

Este proyecto es un fork de [Zalith Launcher 2](https://github.com/ZalithLauncher/ZalithLauncher2), de MovTery y colaboradores, que usa como núcleo [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher).
Se distribuye bajo la licencia **GPL-3.0** (ver [LICENSE](LICENSE)): el código fuente de esta app es público y cualquier versión modificada tiene que seguir siéndolo.

## Compilar

Cada push a GitHub compila un APK de prueba en **Actions → Build CI**. El APK aparece en los artefactos de esa ejecución (`debug apk (arm64)` sirve para casi todos los celulares).

Secretos del repositorio (Settings → Secrets and variables → Actions):

| Secreto | Para qué |
|---|---|
| `OAUTH_CLIENT_ID` | ID de la app de Azure para iniciar sesión con Microsoft |
| `CURSEFORGE_API_KEY` | Opcional: búsquedas en CurseForge |
