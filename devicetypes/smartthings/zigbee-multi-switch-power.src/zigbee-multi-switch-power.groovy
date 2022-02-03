/*
 *  Copyright 2019 SmartThings
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not
 *  use this file except in compliance with the License. You may obtain a copy
 *  of the License at:
 *
 *		http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

metadata {
	definition(name: "Modified ZigBee Multi Switch Power", namespace: "smartthings", author: "darrylong", ocfDeviceType: "oic.d.smartplug", mnmn: "SmartThings", vid: "generic-switch-power") {
		capability "Actuator"
		capability "Configuration"
		capability "Refresh"
		capability "Health Check"
		capability "Switch"
		capability "Power Meter"

		command "childOn", ["string"]
		command "childOff", ["string"]

		fingerprint manufacturer: "Aurora", model: "DoubleSocket50AU", deviceJoinName: "AURORA Outlet 1" //profileId: "0104", inClusters: "0000, 0003, 0004, 0005, 0006, 0008, 0B04", outClusters: "0019" //AURORA SMART DOUBLE SOCKET 1
	}

	tiles(scale: 2) {
		multiAttributeTile(name: "switch", type: "lighting", width: 6, height: 4, canChangeIcon: true) {
			tileAttribute("device.switch", key: "PRIMARY_CONTROL") {
				attributeState "on", label: '${name}', action: "switch.off", icon: "st.switches.light.on", backgroundColor: "#00A0DC", nextState: "turningOff"
				attributeState "off", label: '${name}', action: "switch.on", icon: "st.switches.light.off", backgroundColor: "#ffffff", nextState: "turningOn"
				attributeState "turningOn", label: '${name}', action: "switch.off", icon: "st.switches.light.on", backgroundColor: "#00A0DC", nextState: "turningOff"
				attributeState "turningOff", label: '${name}', action: "switch.on", icon: "st.switches.light.off", backgroundColor: "#ffffff", nextState: "turningOn"
			}
			tileAttribute("power", key: "SECONDARY_CONTROL") {
				attributeState "power", label: '${currentValue} W'
			}
		}
		standardTile("refresh", "device.refresh", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
			state "default", label: "", action: "refresh.refresh", icon: "st.secondary.refresh"
		}

		main "switch"
		details(["switch", "refresh"])
	}
}

def installed() {
	log.debug "Installed"
	updateDataValue("onOff", "catchall")
	createChildDevices()
}

def updated() {
	log.debug "Updated"
	updateDataValue("onOff", "catchall")
	refresh()
}

def parse(String description) {
	Map eventMap = zigbee.getEvent(description)
	Map eventDescMap = zigbee.parseDescriptionAsMap(description)

	if (eventMap) {
		if (eventDescMap?.sourceEndpoint == "01" || eventDescMap?.endpoint == "01") {
			if (eventMap.name == "power") {
				def powerValue
				def div = device.getDataValue("divisor")
				div = div ? (div as int) : 10
				powerValue = (eventMap.value as Integer)/div
				sendEvent(name: "power", value: powerValue)
			}
			else {
				sendEvent(eventMap)
			}
		} else {
			def childDevice = childDevices.find {
				it.deviceNetworkId == "$device.deviceNetworkId:${eventDescMap.sourceEndpoint}" || it.deviceNetworkId == "$device.deviceNetworkId:${eventDescMap.endpoint}"
			}
			if (childDevice) {
				if (eventMap.name == "power") {
					def powerValue
					def div = device.getDataValue("divisor")
					div = div ? (div as int) : 10
					powerValue = (eventMap.value as Integer)/div
					childDevice.sendEvent(name: "power", value: powerValue)
				}
				else {
					childDevice.sendEvent(eventMap)
				}
			} else {
				log.debug "Child device: $device.deviceNetworkId:${eventDescMap.sourceEndpoint} was not found"
			}
		}
	}
}

private void createChildDevices() {
	def numberOfChildDevices = getChildCount()
	log.debug("createChildDevices(), numberOfChildDevices: ${numberOfChildDevices}")

	for(def endpoint : 2..numberOfChildDevices) {
		try {
			log.debug "creating endpoint: ${endpoint}"
			addChildDevice("Child Switch Health Power", "${device.deviceNetworkId}:0${endpoint}", device.hubId,
				[completedSetup: true,
				 label: "${device.displayName[0..-2]}${endpoint}",
				 isComponent: false
				])
		} catch(Exception e) {
			log.debug "Exception: ${e}"
		}
	}
}

def on() {
	zigbee.on()
}

def off() {
	zigbee.off()
}

def childOn(String dni) {
	def childEndpoint = getChildEndpoint(dni)
	zigbee.command(zigbee.ONOFF_CLUSTER, 0x01, "", [destEndpoint: childEndpoint])
}

def childOff(String dni) {
	def childEndpoint = getChildEndpoint(dni)
	zigbee.command(zigbee.ONOFF_CLUSTER, 0x00, "", [destEndpoint: childEndpoint])
}

/**
 * PING is used by Device-Watch in attempt to reach the Device
 * */
def ping() {
	refresh()
}

def refresh() {
	def refreshCommands = zigbee.onOffRefresh() + zigbee.simpleMeteringPowerRefresh() + zigbee.electricMeasurementPowerRefresh()
	def numberOfChildDevices = getChildCount()
	for(def endpoint : 2..numberOfChildDevices) {
		refreshCommands += zigbee.readAttribute(zigbee.ONOFF_CLUSTER, 0x0000, [destEndpoint: endpoint])
		refreshCommands += zigbee.readAttribute(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, 0x050B, [destEndpoint: endpoint])
	}
	log.debug "refreshCommands: $refreshCommands"
	return refreshCommands
}

def configure() {
	log.debug "configure"
	configureHealthCheck()
	def numberOfChildDevices = getChildCount()
	def configurationCommands = zigbee.onOffConfig(0, 120) + zigbee.electricMeasurementPowerConfig()
	for(def endpoint : 2..numberOfChildDevices) {
		configurationCommands += zigbee.configureReporting(zigbee.ONOFF_CLUSTER, 0x0000, 0x10, 0, 120, null, [destEndpoint: endpoint])
		configurationCommands += zigbee.configureReporting(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, 0x050B, 0x29, 1, 600, 0x0005, [destEndpoint: endpoint])
	}
	configurationCommands << refresh()
	log.debug "configurationCommands: $configurationCommands"
	return configurationCommands
}

def configureHealthCheck() {
	log.debug "configureHealthCheck"
	Integer hcIntervalMinutes = 12
	def healthEvent = [name: "checkInterval", value: hcIntervalMinutes * 60, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID]]
	sendEvent(healthEvent)
	childDevices.each {
		it.sendEvent(healthEvent)
	}
}

private getChildEndpoint(String dni) {
	dni.split(":")[-1] as Integer
}

private getChildCount() {
	switch (device.getDataValue("model")) {
		case "9f76c9f31b4c4a499e3aca0977ac4494":
		case "HY0003":
		case "HY0097":
		case "HS2SW3L-EFR-3.0":
		case "E220-KR3N0Z0-HA":
		case "E220-KR3N0Z1-HA":
		case "E220-KR3N0Z2-HA":
		case "ZB-SW03":
		case "JZ-ZB-003":
		case "PM-S340-ZB":
		case "PM-S340R-ZB":
		case "PM-S350-ZB":
		case "ST-S350-ZB":
		case "SBM300Z3":
		case "HS6SW3A-W-EF-3.0":
			return 3
		case "E220-KR4N0Z0-HA":
		case "E220-KR4N0Z1-HA":
		case "E220-KR4N0Z2-HA":
		case "ZB-SW04":
		case "JZ-ZB-004":
		case "SBM300Z4":
			return 4
		case "E220-KR5N0Z0-HA":
		case "E220-KR5N0Z1-HA":
		case "E220-KR5N0Z2-HA":
		case "ZB-SW05":
		case "JZ-ZB-005":
		case "SBM300Z5":
			return 5
		case "E220-KR6N0Z0-HA":
		case "E220-KR6N0Z1-HA":
		case "E220-KR6N0Z2-HA":
		case "ZB-SW06":
		case "JZ-ZB-006":
		case "SBM300Z6":
			return 6
		case "E220-KR2N0Z0-HA":
		case "E220-KR2N0Z1-HA":
		case "E220-KR2N0Z2-HA":
		case "SBM300Z2":
		default:
			return 2
	}
}
