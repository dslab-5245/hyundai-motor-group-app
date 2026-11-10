# Look test: one path-traced (Cycles) still of the film's car on the wet night road, to compare against the real-time
# three.js frame of the same camera preset.
#   blender -b --factory-startup -P blender_still.py -- <CarConcept.glb> <hdri.hdr> <out.png> [samples]
import math
import sys

import bpy
from mathutils import Vector

argv = sys.argv[sys.argv.index("--") + 1:]
glb, hdri, out = argv[0], argv[1], argv[2]
samples = int(argv[3]) if len(argv) > 3 else 192

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
bpy.ops.import_scene.gltf(filepath=glb)

# ---- the car: no logo plate, no emblem, navy paint, live lamps
for o in bpy.data.objects:
    if o.name.startswith("License") or o.name.startswith("InteriorSteeringEmblem"):
        o.hide_render = True


def principled(mat):
    return next((n for n in mat.node_tree.nodes if n.type == "BSDF_PRINCIPLED"), None) if mat.use_nodes else None


def set_in(node, name, value):
    if node is not None and name in node.inputs:
        sock = node.inputs[name]
        for link in list(sock.links):
            node.id_data.links.remove(link)
        sock.default_value = value


for m in bpy.data.materials:
    p = principled(m)
    if p is None:
        continue
    if m.name == "Paint 1 Carmine":
        set_in(p, "Base Color", (0.004, 0.017, 0.08, 1))
        set_in(p, "Metallic", 0.85)
        set_in(p, "Roughness", 0.28)
        set_in(p, "Coat Weight", 1.0)
        set_in(p, "Coat Roughness", 0.03)
        set_in(p, "Normal", (0, 0, 0))
    elif m.name == "Interior 3 Carmine":
        set_in(p, "Base Color", (0.011, 0.02, 0.05, 1))
    elif m.name == "Tireside":
        set_in(p, "Base Color", (0.003, 0.003, 0.003, 1))
    elif m.name == "Headlight":
        set_in(p, "Emission Color", (0.72, 0.81, 1.0, 1))
        set_in(p, "Emission Strength", 40.0)
    elif m.name == "Brakelight":
        set_in(p, "Emission Color", (1.0, 0.02, 0.03, 1))
        set_in(p, "Emission Strength", 12.0)
    elif m.name == "Signallight":
        set_in(p, "Emission Color", (1.0, 0.33, 0.02, 1))
        set_in(p, "Emission Strength", 1.0)
    elif m.name == "Dashboard":
        set_in(p, "Emission Color", (0.27, 0.45, 1.0, 1))
        set_in(p, "Emission Strength", 3.0)

# ---- wet asphalt: dark, glossy where the water stands, a fine bump
bpy.ops.mesh.primitive_plane_add(size=1, location=(0, -100, 0))
road = bpy.context.object
road.scale = (40, 420, 1)
rm = bpy.data.materials.new("Road")
rm.use_nodes = True
nt = rm.node_tree
rp = principled(rm)
rp.inputs["Base Color"].default_value = (0.012, 0.013, 0.016, 1)
coord = nt.nodes.new("ShaderNodeTexCoord")
big = nt.nodes.new("ShaderNodeTexNoise")
big.inputs["Scale"].default_value = 0.35
big.inputs["Detail"].default_value = 3
ramp = nt.nodes.new("ShaderNodeValToRGB")
ramp.color_ramp.elements[0].position = 0.42
ramp.color_ramp.elements[0].color = (0.06, 0.06, 0.06, 1)
ramp.color_ramp.elements[1].position = 0.62
ramp.color_ramp.elements[1].color = (0.45, 0.45, 0.45, 1)
fine = nt.nodes.new("ShaderNodeTexNoise")
fine.inputs["Scale"].default_value = 60
fine.inputs["Detail"].default_value = 6
bump = nt.nodes.new("ShaderNodeBump")
bump.inputs["Strength"].default_value = 0.25
bump.inputs["Distance"].default_value = 0.01
nt.links.new(coord.outputs["Object"], big.inputs["Vector"])
nt.links.new(coord.outputs["Object"], fine.inputs["Vector"])
nt.links.new(big.outputs["Fac"], ramp.inputs["Fac"])
nt.links.new(ramp.outputs["Color"], rp.inputs["Roughness"])
nt.links.new(fine.outputs["Fac"], bump.inputs["Height"])
nt.links.new(bump.outputs["Normal"], rp.inputs["Normal"])
road.data.materials.append(rm)

# lane lines (the car's nose points at -Y after the glTF import)
lm = bpy.data.materials.new("Line")
lm.use_nodes = True
principled(lm).inputs["Base Color"].default_value = (0.55, 0.58, 0.64, 1)
principled(lm).inputs["Roughness"].default_value = 0.5
for x in (-1.85, 1.85):
    for i in range(30):
        bpy.ops.mesh.primitive_plane_add(size=1, location=(x, 10 - i * 12, 0.004))
        d = bpy.context.object
        d.scale = (0.12, 3, 1)
        d.data.materials.append(lm)

# ---- light: a dim night sky, warm street lamps down the road, two long soft panels for the paint
world = bpy.data.worlds.new("Night")
scene.world = world
world.use_nodes = True
wn = world.node_tree
env = wn.nodes.new("ShaderNodeTexEnvironment")
env.image = bpy.data.images.load(hdri)
bg = wn.nodes["Background"]
bg.inputs["Strength"].default_value = 0.6
wn.links.new(env.outputs["Color"], bg.inputs["Color"])


def lamp(kind, loc, energy, color, size=1.0, size_y=None, rot=(0, 0, 0)):
    data = bpy.data.lights.new(kind, kind)
    data.energy = energy
    data.color = color
    if kind == "AREA":
        data.shape = "RECTANGLE"
        data.size = size
        data.size_y = size_y or size
    else:
        data.shadow_soft_size = size
    ob = bpy.data.objects.new(kind, data)
    ob.location = loc
    ob.rotation_euler = rot
    scene.collection.objects.link(ob)
    return ob


for i in range(8):
    for x in (-7.4, 7.4):
        lamp("POINT", (x * 0.66, 14 - i * 36, 6.8), 2600, (1.0, 0.82, 0.6), 0.25)
lamp("AREA", (0, 0.5, 5.5), 420, (0.75, 0.83, 1.0), 7, 1.4)
lamp("AREA", (-6.5, 0, 1.7), 260, (0.75, 0.83, 1.0), 6, 0.7, rot=(0, -math.radians(78), 0))
lamp("AREA", (6.5, 0, 1.7), 260, (0.75, 0.83, 1.0), 6, 0.7, rot=(0, math.radians(78), 0))
for x in (-0.72, 0.72):   # headlight beams
    s = lamp("SPOT", (x, -2.3, 0.66), 5200, (0.86, 0.9, 1.0), 0.05)
    s.data.spot_size = math.radians(50)
    s.data.spot_blend = 0.8
    s.rotation_euler = (math.radians(86), 0, 0)

# ---- camera: the film's frontQR preset ([3.9, 1.05, -6.2] -> [-0.1, 0.48, -0.6], fov 34 vertical), in Blender axes
cam_data = bpy.data.cameras.new("Cam")
cam = bpy.data.objects.new("Cam", cam_data)
scene.collection.objects.link(cam)
scene.camera = cam
cam.location = Vector((-3.9, -6.2, 1.05))
look = Vector((0.1, -0.6, 0.48))
cam.rotation_euler = (look - cam.location).to_track_quat("-Z", "Y").to_euler()
cam_data.sensor_fit = "VERTICAL"
cam_data.angle = math.radians(34)
cam_data.dof.use_dof = True
cam_data.dof.focus_distance = (look - cam.location).length
cam_data.dof.aperture_fstop = 2.8

# ---- render
scene.render.engine = "CYCLES"
scene.render.resolution_x = 1920
scene.render.resolution_y = 1080
scene.cycles.samples = samples
scene.cycles.use_denoising = True
scene.view_settings.view_transform = "AgX"
scene.view_settings.look = "AgX - Medium High Contrast"
prefs = bpy.context.preferences.addons["cycles"].preferences
for kind in ("OPTIX", "CUDA"):
    try:
        prefs.compute_device_type = kind
        prefs.get_devices()
        if any(d.type == kind for d in prefs.devices):
            for d in prefs.devices:
                d.use = d.type == kind
            scene.cycles.device = "GPU"
            if kind == "OPTIX":
                scene.cycles.denoiser = "OPTIX"
            print("cycles device:", kind)
            break
    except Exception as e:  # noqa: BLE001
        print("device", kind, "unavailable:", e)
scene.render.filepath = out
bpy.ops.render.render(write_still=True)
print("wrote", out)
