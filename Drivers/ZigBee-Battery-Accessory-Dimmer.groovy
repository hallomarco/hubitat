/**
 *	Device Driver for Hubitat Elevation
 *   	V 0.1
 *
 *	Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *	in compliance with the License. You may obtain a copy of the License at:
 *
 *		http://www.apache.org/licenses/LICENSE-2.0
 *
 *	Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *	on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *	for the specific language governing permissions and limitations under the License.
 *
 *   Based on ZigBee Battery Accessory Dimmer by smartthings adapted for Hubitat Elevation by at9
 *   Original source: https://github.com/SmartThingsCommunity/SmartThingsPublic/blob/master/devicetypes/smartthings/zigbee-battery-accessory-dimmer.src/zigbee-battery-accessory-dimmer.groovy
 * 
 *   Changes:
 *   Removed Tiles
 *   Added in Pushable buttons for Sengled Smart Switch
 *   Added in debug logging toggle
 *   Added in user input for change in dimming levels
 */

import hubitat.zigbee.zcl.DataType

metadata {
	definition (name: "ZigBee Battery Accessory Dimmer WIP", namespace: "at9", author: "at9", ocfDeviceType: "oic.d.switch", importUrl: "https://raw.githubusercontent.com/at-9/hubitat/master/Drivers/ZigBee-Battery-Accessory-Dimmer.groovy") {
		capability "Actuator"
		capability "Battery"
		capability "Configuration"
		capability "Switch"
		capability "Switch Level"
        	capability "PushableButton"

		// Sengled Switch is moved to the CST because of issues with battery reports so our way to resolve this is to hide the battery in OneApp by using metadata without it.
		fingerprint profileId: "0104", inClusters: "0000,0001,0003,0020,FC11", outClusters: "0003,0004,0006,0008,FC10", manufacturer: "sengled", model: "E1E-G7F", deviceJoinName: "Sengled Smart Switch", mnmn:"SmartThings", vid: "generic-dimmer"
		fingerprint manufacturer: "IKEA of Sweden", model: "TRADFRI wireless dimmer", deviceJoinName: "IKEA TRÅDFRI Wireless dimmer" // 01 [0104 or C05E] 0810 02 06 0000 0001 0003 0009 0B05 1000 06 0003 0004 0006 0008 0019 1000
		fingerprint profileId: "0104", inClusters: "0000,0001,0003,0020,0B05", outClusters: "0003,0006,0008,0019", manufacturer: "Centralite Systems", model: "3131-G", deviceJoinName: "Centralite Smart Switch"
	}

}
preferences {
    section("Time") {
        input "Sstep", "number", title: "Single dimmer Change as %", defaultValue: "5", required: true
        input "Dstep", "number", title: "Double dimmer Change as %", defaultValue: "10", required: true
        input name: "debuglog", type: "bool", title: "Enable debug logging", defaultValue: false

    }
}

def getDOUBLE_STEP() { Dstep as int }
def getSTEP() { Sstep as int }

def getONOFF_ON_COMMAND() { 0x0001 }
def getONOFF_OFF_COMMAND() { 0x0000 }
def getLEVEL_MOVE_LEVEL_COMMAND() { 0x0000 }
def getLEVEL_MOVE_COMMAND() { 0x0001 }
def getLEVEL_STEP_COMMAND() { 0x0002 }
def getLEVEL_STOP_COMMAND() { 0x0003 }
def getLEVEL_MOVE_LEVEL_ONOFF_COMMAND() { 0x0004 }
def getLEVEL_MOVE_ONOFF_COMMAND() { 0x0005 }
def getLEVEL_STEP_ONOFF_COMMAND() { 0x0006 }
def getLEVEL_STOP_ONOFF_COMMAND() { 0x0007 }
def getLEVEL_DIRECTION_UP() { "00" }
def getLEVEL_DIRECTION_DOWN() { "01" }

def getBATTERY_VOLTAGE_ATTR() { 0x0020 }
def getBATTERY_PERCENT_ATTR() { 0x0021 }

def getMFR_SPECIFIC_CLUSTER() { 0xFC10 }

def getUINT8_STR() { "20" }


private boolean isIkeaDimmer() {
	device.getDataValue("model") == "TRADFRI wireless dimmer"
}
private boolean isSengledSwitch() {
	device.getDataValue("model") == "E1E-G7F"
}
private boolean isCentraliteSwitch() {
	device.getDataValue("model") == "3131-G"
}

def parse(String description) {
	if (debuglog) log.debug "description is $description"
	def results = []

	def event = zigbee.getEvent(description)
	if (event) {
		results << createEvent(event)
	} else {
		def descMap = zigbee.parseDescriptionAsMap(description)

		if (descMap.clusterInt == zigbee.POWER_CONFIGURATION_CLUSTER) {
			results = handleBatteryEvents(descMap)
		} else if (isSengledSwitch()) {
			results = handleSengledSwitchEvents(descMap)
		} else if (descMap.clusterInt == zigbee.ONOFF_CLUSTER) {
			results = handleSwitchEvent(descMap)
		} else if (descMap.clusterInt == zigbee.LEVEL_CONTROL_CLUSTER) {
			if (isCentraliteSwitch()) {
				results = handleCentraliteSmartSwitchLevelEvent(descMap)
			} else if (isIkeaDimmer()) {
				results = handleIkeaDimmerLevelEvent(descMap)
			}
		} else {
			log.warn "DID NOT PARSE MESSAGE for description : $description"
			log.debug "${descMap}"
		}
	}

	if (debuglog) log.debug "parse returned $results"
	return results
}

def handleSengledSwitchEvents(descMap) {
	def results = []

	if (descMap?.clusterInt == MFR_SPECIFIC_CLUSTER && descMap.data) {
		def currentLevel = device.currentValue("level") as Integer ?: 0
		def value = currentLevel

		switch (descMap.data[0]) {
			case '01':
				//short press of 'ON' button
				results << createEvent(name: "switch", value: "on",isStateChange:true)
				break
			case '02':
				// move up
				if (descMap.data[2] == '02') {
					//long press of 'BRIGHTEN' button
					value = Math.min(currentLevel + DOUBLE_STEP, 100)
				} else if (descMap.data[2] == '01') {
					//short press of 'BRIGHTEN' button
					value = Math.min(currentLevel + STEP, 100)
				} else {
					log.info "Invalid value ${descMap.data[2]} received for descMap.data[2]"
				}

				results << createEvent(name: "switch", value: "on",isStateChange:true)
				results << createEvent(name: "level", value: value,isStateChange:true)
				break
			case '03':
				//move down
				if (descMap.data[2] == '02') {
					//long press of 'DIM' button
					value = Math.max(currentLevel - DOUBLE_STEP, 0)
				} else if (descMap.data[2] == '01') {
					//short press of 'DIM' button
					value = Math.max(currentLevel - STEP, 0)
				} else {
					log.info "Invalid value ${descMap.data[2]} received for descMap.data[2]"
				}

				if (value == 0) {
					results << createEvent(name: "switch", value: "off",isStateChange:true)
				} else {
					results << createEvent(name: "level", value: value,isStateChange:true)
				}
				break
			case '04':
				//short press of 'OFF' button
				results << createEvent(name: "switch", value: "off",isStateChange:true)
				break
			case '05':
				//double tap of 'ON' button
				results << createEvent(name: "pushed", value: "1",descriptionText: "Button 1 was pushed") 
				break
            case '06':
				//long press of 'ON' button
				results << createEvent(name: "pushed", value: "2",descriptionText: "Button 2 was pushed") 
				break
            case '07':
				//double tap of 'OFF' button
				results << createEvent(name: "pushed", value: "3",descriptionText: "Button 3 was pushed") 
				break
			case '08':
				//long press of 'OFF' button
				results << createEvent(name: "pushed", value: "4",descriptionText: "Button 4 was pushed") 
				break
			default:
				break
		}
	}

	return results
}

def handleCentraliteSmartSwitchLevelEvent(descMap) {
	def results = []

	if (descMap.commandInt == LEVEL_MOVE_ONOFF_COMMAND) {
		// device is sending 0x05 command while long pressing the upper button
		results = handleStepEvent(LEVEL_DIRECTION_UP, descMap)
	} else if (descMap.commandInt == LEVEL_MOVE_COMMAND) {
		//device is sending 0x01 command while long pressing the bottom button
		results = handleStepEvent(LEVEL_DIRECTION_DOWN, descMap)
	}

	return results
}

def handleIkeaDimmerLevelEvent(descMap) {
	def results = []

	if (descMap.commandInt == LEVEL_STEP_COMMAND) {
		results = handleStepEvent(descMap.data[0], descMap)
	} else if (descMap.commandInt == LEVEL_MOVE_COMMAND || descMap.commandInt == LEVEL_MOVE_ONOFF_COMMAND) {
		// Treat Level Move and Level Move with On/Off as Level Step
		results = handleStepEvent(descMap.data[0], descMap)
	} else if (descMap.commandInt == LEVEL_STOP_COMMAND || descMap.commandInt == LEVEL_STOP_ONOFF_COMMAND) {
		// We are not going to handle this event because we are not implementing this the way that the Zigbee spec indicates
		if (debuglog) log.debug "Received stop move - not handling"
	} else if (descMap.commandInt == LEVEL_MOVE_LEVEL_ONOFF_COMMAND) {
		// The spec defines this as "Move to level with on/off". The IKEA Dimmer sends us 0x00 or 0xFF only, so we will treat this more as a
		// on/off command for the dimmer. Otherwise, we will treat this as off or on and setLevel.
		if (descMap.data[0] == "00") {
			results << createEvent(name: "switch", value: "off", isStateChange: true)
		} else if (descMap.data[0] == "FF") {
			// The IKEA Dimmer sends 0xFF -- this is technically not to spec, but we will treat this as an "on"
			if (device.currentValue("level") == 0) {
				results << createEvent(name: "level", value: DOUBLE_STEP)
			}

			results << createEvent(name: "switch", value: "on", isStateChange: true)
		} else {
			results << createEvent(name: "switch", value: "on", isStateChange: true)
			// Handle the Zigbee level the same way as we would normally with the same code path -- commandInt doesn't matter right now
			// The first byte is the level, the second two bytes are the rate -- we only care about the level right now.
			results << createEvent(zigbee.getEventFromAttrData(descMap.clusterInt, descMap.commandInt, UINT8_STR, descMap.data[0]))
		}
	}

	return results
}

def handleSwitchEvent(descMap) {
	def results = []

	if (descMap.commandInt == ONOFF_ON_COMMAND) {
		if (device.currentValue("level") == 0) {
			results << createEvent(name: "level", value: DOUBLE_STEP)
		}
		results << createEvent(name: "switch", value: "on")
	} else if (descMap.commandInt == ONOFF_OFF_COMMAND) {
		results << createEvent(name: "switch", value: "off")
	}

	return results
}

def handleStepEvent(direction, descMap) {
	def results = []
	def currentLevel = device.currentValue("level") as Integer ?: 0
	def value = null

	if (direction == LEVEL_DIRECTION_UP) {
		value = Math.min(currentLevel + DOUBLE_STEP, 100)
	} else if (direction == LEVEL_DIRECTION_DOWN) {
		value = Math.max(currentLevel - DOUBLE_STEP, 0)
	}

	if (value != null) {
		if (debuglog) log.debug "Step ${direction == LEVEL_DIRECTION_UP ? "up" : "down"} by $DOUBLE_STEP to $value"

		// don't change level if switch will be turning off
		if (value == 0) {
			results << createEvent(name: "switch", value: "off")
		} else {
			results << createEvent(name: "switch", value: "on")
			results << createEvent(name: "level", value: value)
		}
	} else {
		if (debuglog) log.debug "Received invalid direction ${direction} - descMap.data = ${descMap.data}"
	}

	return results
}

def handleBatteryEvents(descMap) {
	def results = []

	if (descMap.value) {
		def rawValue = zigbee.convertHexToInt(descMap.value)
		def batteryValue = null

		if (rawValue == 0xFF) {
			// Log invalid readings to info for analytics and skip sending an event.
			// This would be a good thing to watch for and form some sort of device health alert if too many come in.
			log.info "Invalid battery reading returned"
		} else if (descMap.attrInt == BATTERY_VOLTAGE_ATTR && !isIkeaDimmer()) { // Ignore from IKEA Dimmer if it sends this since it is probably 0
			def minVolts = 2.3
			def maxVolts = 3.0
			def batteryValueVoltage = rawValue / 10

			batteryValue = Math.round(((batteryValueVoltage - minVolts) / (maxVolts - minVolts)) * 100)
		} else if (descMap.attrInt == BATTERY_PERCENT_ATTR) {
			// The IKEA dimmer is sending us full percents, but the spec tells us these are half percents, so account for this
			batteryValue = Math.round(rawValue / (isIkeaDimmer() ? 1 : 2))
		}

		if (batteryValue != null) {
			batteryValue = Math.min(100, Math.max(0, batteryValue))

			results << createEvent(name: "battery", value: batteryValue, unit: "%", descriptionText: "{{ device.displayName }} battery was {{ value }}%", translatable: true)
		}
	}

	return results
}

def off() {
	sendEvent(name: "switch", value: "off", isStateChange: true)
}

def on() {
	sendEvent(name: "switch", value: "on", isStateChange: true)
}

def setLevel(value, rate = null) {
	if (value == 0) {
		sendEvent(name: "switch", value: "off")
		// OneApp expects a level event when the dimmer value is changed
		value = device.currentValue("level")
	} else {
		sendEvent(name: "switch", value: "on")
	}
	runIn(1, delayedSend, [data: createEvent(name: "level", value: value), overwrite: true])
}

def delayedSend(data) {
	sendEvent(data)
}

def ping() {
	if (isCentraliteSwitch()) {
		zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, BATTERY_VOLTAGE_ATTR)
	} else {
		zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, BATTERY_PERCENT_ATTR)
	}
}

def installed() {
	sendEvent(name: "switch", value: "on")
	sendEvent(name: "level", value: 100)
    
    if (isSengledSwitch()) sendEvent(name: "numberOfButtons", value: 4)
}

def configure() {
	def offlinePingable = isIkeaDimmer() ? "0" : "1" // We can't ping the IKEA dimmer, so tell device health this
	int reportInterval = 3 * 60 * 60
    
    if (isSengledSwitch()) sendEvent(name: "numberOfButtons", value: 4)
    
	// The checkInterval is twice the reportInterval plus lag (1-2 mins allowable)
	sendEvent(name: "checkInterval", value: 2 * 60 + 2 * reportInterval, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID, offlinePingable: offlinePingable], displayed: false)

	if (isCentraliteSwitch()) {
		zigbee.addBinding(zigbee.ONOFF_CLUSTER) + zigbee.addBinding(zigbee.LEVEL_CONTROL_CLUSTER) +
			zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, BATTERY_VOLTAGE_ATTR) +
			zigbee.batteryConfig(0, reportInterval, null)
	} else {
		zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, BATTERY_PERCENT_ATTR) +
			// Report no more frequently than 30 seconds, no less frequently than 6 hours, and when there is a change of 10% (expressed as half percents)
			zigbee.configureReporting(zigbee.POWER_CONFIGURATION_CLUSTER, BATTERY_PERCENT_ATTR, DataType.UINT8, 30, reportInterval, 20)
	}
}
