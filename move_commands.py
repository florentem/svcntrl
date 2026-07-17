import re

with open('src/main/java/com/svcntrl/command/SvcntrlCommands.java', 'r') as f:
    c = f.read()

# The original nodes to remove
tp_node_regex = r'\s*\.then\(literal\("tp"\)\.requires\(requirePerm\("svcntrl\.command\.tp"\)\)\n\s*\.then\(argument\("name", StringArgumentType\.word\(\)\)\n\s*\.suggests\(\(ctx, builder\) -> suggestProjects\(ctx, builder\)\)\n\s*\.executes\(\(ctx\) -> executeTp\(ctx\.getSource\(\), StringArgumentType\.getString\(ctx, "name"\)\)\)\)\)\n'

select_node_regex = r'\s*\.then\(literal\("select"\)\.requires\(requirePerm\("svcntrl\.command\.select"\)\)\n\s*\.then\(literal\("raycast"\)\n\s*\.executes\(\(ctx\) -> executeSelectRaycast\(ctx\.getSource\(\)\)\)\)\n\s*\.then\(argument\("name", StringArgumentType\.word\(\)\)\n\s*\.suggests\(\(ctx, builder\) -> suggestProjects\(ctx, builder\)\)\n\s*\.executes\(\(ctx\) -> executeSelect\(ctx\.getSource\(\), StringArgumentType\.getString\(ctx, "name"\)\)\)\)\)\n'

# Find the project node end
project_end_regex = r'(\s*\.then\(literal\("untrust"\)\.requires\(requirePerm\("svcntrl\.command\.project\.untrust"\)\)\n\s*\.then\(argument\("player", StringArgumentType\.word\(\)\)\n\s*\.suggests\(\(ctx, builder\) -> suggestMembers\(ctx, builder\)\)\n\s*\.executes\(\(ctx\) -> executeUntrust\(ctx\.getSource\(\), StringArgumentType\.getString\(ctx, "player"\)\)\)\)\)\n\s*\))'

tp_match = re.search(tp_node_regex, c)
select_match = re.search(select_node_regex, c)

if tp_match and select_match:
    c = c.replace(tp_match.group(0), '')
    c = c.replace(select_match.group(0), '')
    
    # insert inside project
    insertion = """                    .then(literal("tp").requires(requirePerm("svcntrl.command.project.tp"))
                        .then(argument("name", StringArgumentType.word())
                            .suggests((ctx, builder) -> suggestProjects(ctx, builder))
                            .executes(ctx -> executeTp(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                    .then(literal("select").requires(requirePerm("svcntrl.command.project.select"))
                        .then(literal("raycast")
                            .executes(ctx -> executeSelectRaycast(ctx.getSource())))
                        .then(argument("name", StringArgumentType.word())
                            .suggests((ctx, builder) -> suggestProjects(ctx, builder))
                            .executes(ctx -> executeSelect(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                )"""
    c = re.sub(project_end_regex, insertion, c)
    
    with open('src/main/java/com/svcntrl/command/SvcntrlCommands.java', 'w') as f:
        f.write(c)
    print("Success")
else:
    print("Failed to find matches")
