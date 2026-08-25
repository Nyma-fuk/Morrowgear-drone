package jp.morrowgear.drone.client;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

public final class DroneModel extends EntityModel<DroneRenderState> {
	private final ModelPart frame;
	private final ModelPart fieldFrame;
	private final ModelPart scoutFrame;
	private final ModelPart cargoFrame;
	private final ModelPart engineerFrame;
	private final ModelPart securityFrame;
	private final ModelPart rotorFl;
	private final ModelPart rotorFr;
	private final ModelPart rotorBl;
	private final ModelPart rotorBr;

	public DroneModel(ModelPart root) {
		super(root);
		this.frame = root.getChild("frame");
		this.fieldFrame = frame.getChild("field_frame");
		this.scoutFrame = frame.getChild("scout_frame");
		this.cargoFrame = frame.getChild("cargo_frame");
		this.engineerFrame = frame.getChild("engineer_frame");
		this.securityFrame = frame.getChild("security_frame");
		this.rotorFl = frame.getChild("rotor_fl");
		this.rotorFr = frame.getChild("rotor_fr");
		this.rotorBl = frame.getChild("rotor_bl");
		this.rotorBr = frame.getChild("rotor_br");
	}

	public static LayerDefinition createBodyLayer() {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();
		PartDefinition frame = root.addOrReplaceChild("frame", CubeListBuilder.create(), PartPose.offset(0, 18, 0));
		frame.addOrReplaceChild("body", CubeListBuilder.create()
			.texOffs(0, 0).addBox(-5, -3, -4, 10, 4, 8)
			.texOffs(0, 16).addBox(-3.5f, -4.5f, -3, 7, 1.5f, 6)
			.texOffs(28, 0).addBox(-2.5f, -2.5f, -5.5f, 5, 2.5f, 1.5f)
			.texOffs(0, 26).addBox(-9, -2, -1, 18, 1.5f, 2)
			.texOffs(0, 30).addBox(-1, -2, -9, 2, 1.5f, 18),
			PartPose.ZERO);
		addFieldFrame(frame);
		addScoutFrame(frame);
		addCargoFrame(frame);
		addEngineerFrame(frame);
		addSecurityFrame(frame);
		addRotor(frame, "rotor_fl", -9, -2, -9);
		addRotor(frame, "rotor_fr", 9, -2, -9);
		addRotor(frame, "rotor_bl", -9, -2, 9);
		addRotor(frame, "rotor_br", 9, -2, 9);
		return LayerDefinition.create(mesh, 64, 64);
	}

	private static void addFieldFrame(PartDefinition frame) {
		frame.addOrReplaceChild("field_frame", CubeListBuilder.create()
			.texOffs(36, 16).addBox(-4.6f, -1, -3, 1, 4, 1)
			.texOffs(36, 16).addBox(3.6f, -1, -3, 1, 4, 1)
			.texOffs(36, 16).addBox(-4.6f, -1, 2, 1, 4, 1)
			.texOffs(36, 16).addBox(3.6f, -1, 2, 1, 4, 1)
			.texOffs(40, 16).addBox(-5.2f, 3, -4.4f, 1.6f, .9f, 8.8f)
			.texOffs(40, 16).addBox(3.6f, 3, -4.4f, 1.6f, .9f, 8.8f), PartPose.ZERO);
	}

	private static void addScoutFrame(PartDefinition frame) {
		PartDefinition scout = frame.addOrReplaceChild("scout_frame", CubeListBuilder.create()
			.texOffs(52, 20).addBox(-2.5f, -5.6f, -1.5f, 5, 1.1f, 3)
			.texOffs(56, 24).addBox(-1.8f, -7.4f, -.5f, .8f, 2, 1)
			.texOffs(56, 24).addBox(1, -7.4f, -.5f, .8f, 2, 1), PartPose.ZERO);
		addSplayedStrut(scout, "scout_leg_l", -4.2f, -.24f);
		addSplayedStrut(scout, "scout_leg_r", 4.2f, .24f);
		scout.addOrReplaceChild("scout_skids", CubeListBuilder.create()
			.texOffs(40, 16).addBox(-6, 3.1f, -4.2f, 1.3f, .8f, 8.4f)
			.texOffs(40, 16).addBox(4.7f, 3.1f, -4.2f, 1.3f, .8f, 8.4f), PartPose.ZERO);
	}

	private static void addSplayedStrut(PartDefinition root, String name, float x, float zRotation) {
		root.addOrReplaceChild(name, CubeListBuilder.create().texOffs(36, 16)
			.addBox(-.55f, 0, -.55f, 1.1f, 4.1f, 1.1f), PartPose.offsetAndRotation(x, -1, 0, 0, 0, zRotation));
	}

	private static void addCargoFrame(PartDefinition frame) {
		frame.addOrReplaceChild("cargo_frame", CubeListBuilder.create()
			.texOffs(0, 42).addBox(-7.2f, -2.3f, -3.7f, 3, 3.8f, 7.4f)
			.texOffs(0, 42).addBox(4.2f, -2.3f, -3.7f, 3, 3.8f, 7.4f)
			.texOffs(24, 42).addBox(-3.2f, .5f, -3.2f, 6.4f, 2.3f, 6.4f)
			.texOffs(36, 16).addBox(-5.3f, -1, -3, 1.2f, 4, 1.2f)
			.texOffs(36, 16).addBox(4.1f, -1, -3, 1.2f, 4, 1.2f)
			.texOffs(36, 16).addBox(-5.3f, -1, 2, 1.2f, 4, 1.2f)
			.texOffs(36, 16).addBox(4.1f, -1, 2, 1.2f, 4, 1.2f)
			.texOffs(40, 16).addBox(-5.9f, 3, -4.8f, 1.8f, 1, 9.6f)
			.texOffs(40, 16).addBox(4.1f, 3, -4.8f, 1.8f, 1, 9.6f), PartPose.ZERO);
	}

	private static void addEngineerFrame(PartDefinition frame) {
		PartDefinition engineer = frame.addOrReplaceChild("engineer_frame", CubeListBuilder.create()
			.texOffs(24, 42).addBox(-3.4f, -6.5f, -2.8f, 6.8f, 2.2f, 5.6f)
			.texOffs(52, 20).addBox(-1.4f, 1, -1.4f, 2.8f, 3.4f, 2.8f), PartPose.ZERO);
		for (int side : new int[]{-1, 1}) {
			for (float z : new float[]{-2.7f, 2.2f}) {
				engineer.addOrReplaceChild("engineer_leg_" + side + "_" + z,
					CubeListBuilder.create().texOffs(36, 16).addBox(-.7f, 0, -.7f, 1.4f, 4.2f, 1.4f),
					PartPose.offsetAndRotation(side * 4.2f, -1, z, 0, 0, side * .22f));
			}
		}
		engineer.addOrReplaceChild("engineer_feet", CubeListBuilder.create()
			.texOffs(40, 16).addBox(-7, 3.2f, -4.2f, 3, .9f, 3)
			.texOffs(40, 16).addBox(4, 3.2f, -4.2f, 3, .9f, 3)
			.texOffs(40, 16).addBox(-7, 3.2f, 1.2f, 3, .9f, 3)
			.texOffs(40, 16).addBox(4, 3.2f, 1.2f, 3, .9f, 3), PartPose.ZERO);
	}

	private static void addSecurityFrame(PartDefinition frame) {
		PartDefinition security = frame.addOrReplaceChild("security_frame", CubeListBuilder.create()
			.texOffs(0, 42).addBox(-8, -3.2f, -5.4f, 4.2f, 4.8f, 4.6f)
			.texOffs(0, 42).addBox(3.8f, -3.2f, -5.4f, 4.2f, 4.8f, 4.6f)
			.texOffs(24, 42).addBox(-3.6f, -5.8f, -.8f, 7.2f, 1.4f, 2.6f), PartPose.ZERO);
		for (int side : new int[]{-1, 1}) {
			for (float z : new float[]{-2.8f, 2f}) {
				security.addOrReplaceChild("security_leg_" + side + "_" + z,
					CubeListBuilder.create().texOffs(36, 16).addBox(-.9f, 0, -.9f, 1.8f, 4, 1.8f),
					PartPose.offsetAndRotation(side * 4.4f, -1, z, 0, 0, side * .18f));
			}
		}
		security.addOrReplaceChild("security_feet", CubeListBuilder.create()
			.texOffs(40, 16).addBox(-7.3f, 3.1f, -4.4f, 3.4f, 1, 3.2f)
			.texOffs(40, 16).addBox(3.9f, 3.1f, -4.4f, 3.4f, 1, 3.2f)
			.texOffs(40, 16).addBox(-7.3f, 3.1f, 1.2f, 3.4f, 1, 3.2f)
			.texOffs(40, 16).addBox(3.9f, 3.1f, 1.2f, 3.4f, 1, 3.2f), PartPose.ZERO);
	}

	private static void addRotor(PartDefinition root, String name, float x, float y, float z) {
		root.addOrReplaceChild(name, CubeListBuilder.create()
			.texOffs(48, 0).addBox(-1, -1, -1, 2, 2, 2)
			.texOffs(0, 38).addBox(-6, -0.2f, -0.4f, 12, 0.35f, 0.8f)
			.texOffs(0, 40).addBox(-0.4f, -0.2f, -6, 0.8f, 0.35f, 12),
			PartPose.offset(x, y, z));
	}

	@Override
	public void setupAnim(DroneRenderState state) {
		super.setupAnim(state);
		frame.xRot = state.flightPitch;
		frame.zRot = state.flightRoll;
		fieldFrame.visible = state.role == jp.morrowgear.drone.DroneRole.FIELD;
		scoutFrame.visible = state.role == jp.morrowgear.drone.DroneRole.SCOUT;
		cargoFrame.visible = state.role == jp.morrowgear.drone.DroneRole.CARGO;
		engineerFrame.visible = state.role == jp.morrowgear.drone.DroneRole.ENGINEER;
		securityFrame.visible = state.role == jp.morrowgear.drone.DroneRole.SECURITY;
		float angle = state.docked ? 0 : state.ageInTicks * 1.8f;
		rotorFl.yRot = angle;
		rotorBr.yRot = angle;
		rotorFr.yRot = -angle;
		rotorBl.yRot = -angle;
	}
}
