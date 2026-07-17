import re

with open('src/main/java/com/svcntrl/data/ProjectManager.java', 'r') as f:
    c = f.read()

c = c.replace('bObj.add("manualSnapshots", serializeSnapshotList(branch.getManualSnapshots()));',
              'synchronized(branch.getManualSnapshots()) { bObj.add("manualSnapshots", serializeSnapshotList(branch.getManualSnapshots())); }')

c = c.replace('bObj.add("autoSnapshots", serializeSnapshotList(branch.getAutoSnapshots()));',
              'synchronized(branch.getAutoSnapshots()) { bObj.add("autoSnapshots", serializeSnapshotList(branch.getAutoSnapshots())); }')

c = c.replace('lastPrefsFuture = com.svcntrl.SvcntrlMod.runAsync(() -> {',
              'lastPrefsFuture = lastPrefsFuture == null ? com.svcntrl.SvcntrlMod.runAsync(() -> {')
c = c.replace('lastPrefsFuture == null ? com.svcntrl.SvcntrlMod.runAsync(() -> {',
              'lastPrefsFuture == null ? com.svcntrl.SvcntrlMod.runAsync(() -> {\n            try { savePrefsSync(); } catch(Exception e) {} \n        }) : lastPrefsFuture.thenRunAsync(() -> {\n            try { savePrefsSync(); } catch(Exception e) {} \n        }, com.svcntrl.SvcntrlMod.getExecutor());\n        //')

with open('src/main/java/com/svcntrl/data/ProjectManager.java', 'w') as f:
    f.write(c)
