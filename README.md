# TropiFix

Petit mod client Fabric (Minecraft 1.21.1) qui corrige des bugs de Cobblemon
rencontres en jeu sur Tropimon. Uniquement des mixins defensifs : aucun ajout
de fonctionnalite, aucun changement de comportement une fois le bug evite.

## Correctifs inclus

| Correctif | Probleme corrige |
|---|---|
| `PcGuiDragCrashMixin` | Crash `UninitializedPropertyAccessException: lateinit property storageWidget` quand on clique-glisse dans l'ecran du PC avant que les boites soient chargees (Cobblemon 1.7.x). |
| `WorldChunkClearCrashMixin` | Deconnexion "Erreur de protocole reseau" (`NullPointerException` dans `WorldChunk.clear`) quand Farsight recharge un chunk dont le cache est corrompu. |
| `ChatRefreshCrashMixin` | Crash `ConcurrentModificationException` dans `ChatHud.refresh` a la fermeture du tchat (declenche par Tropimon Chat Filter quand la liste des messages change pendant la reconstruction). |
| `OfflinePacketCrashMixin` | Crash `Cannot send packets when not in game!` quand TropimodClient renvoie le resultat d'une notification juste apres une deconnexion : le paquet est abandonne au lieu de faire planter le jeu. |

## Minuteur de raid

Des qu'une notification Tropimon annonce un Raid, un Mega Raid ou un boss mondial,
un minuteur de 5 minutes apparait a l'ecran. Il bipe quand il reste 1 minute et
30 secondes, puis disparait sans bruit a 0.

- Deplacer : ouvrir le tchat (T) puis cliquer-glisser le minuteur.
- Fermer le minuteur en cours : clic droit dessus, tchat ouvert.
- Touches (Options > Controles, categorie TropiFix, non assignees par defaut) :
  "Verrouiller le minuteur" et "Tester le minuteur (1 min 10)".
- Position et verrouillage gardes dans `config/tropifix_timer.properties`.

## Radar par-dessus la monture

TropimodClient masque son radar quand la barre d'endurance / de saut de la monture est
utilisee (en vol par exemple). TropiFix redessine le radar par-dessus cette barre, au meme
endroit, uniquement quand le radar suit ou detecte quelque chose ; sinon la barre
d'endurance reste visible.

## Sons coupes

Les sons des distributeurs / droppers (tir, lancer, clic a vide) et des wagonnets en
mouvement (roulement, interieur) et des cisailles (tonte, coupe de plantes, ruches,
citrouilles) sont coupes ; les autres sons de blocs restent normaux.

## Relache multiple dans le PC

Sous l'ecran des boites : "Selection multiple" (le clic gauche maintenu et glisse
selectionne les Pokemon au lieu de les deplacer), "Tout selectionner" (toute la boite) et
"Relacher (N)", qui demande confirmation avant d'envoyer la relache au serveur. La selection
ne vaut que pour la boite affichee.

## Build

Compile via GitHub Actions a chaque push sur `main`. Le jar se recupere dans
les artifacts du workflow.
