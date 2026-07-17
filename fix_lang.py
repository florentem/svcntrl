import re
import json

def generate_key(text):
    clean = re.sub(r'[^a-zA-Z0-9 ]', '', text.lower()).strip()
    words = clean.split()
    key = '_'.join(words[:5])
    if not key:
        key = 'text'
    return f"svcntrl.msg.{key}"

with open('src/main/java/com/svcntrl/command/SvcntrlCommands.java', 'r') as f:
    content = f.read()

en_us_path = 'src/main/resources/assets/svcntrl/lang/en_us.json'
ru_ru_path = 'src/main/resources/assets/svcntrl/lang/ru_ru.json'

with open(en_us_path, 'r') as f:
    en_us = json.load(f)

with open(ru_ru_path, 'r') as f:
    ru_ru = json.load(f)

# Find all Text.literal("some string") without string concatenation
# Wait, it's complicated to do AST replacement in Python for Java.
# Let's just do a few manual important replacements if needed, or leave the rest.

