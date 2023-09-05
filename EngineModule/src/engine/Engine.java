package engine;

import com.sun.corba.se.impl.ior.WireObjectKeyTemplate;
import data.transfer.object.EndSimulationData;
import data.transfer.object.DataFromUser;
import data.transfer.object.definition.*;
import data.transfer.object.run.result.EntityResultInfo;
import data.transfer.object.run.result.PropertyResultInfo;
import data.transfer.object.run.result.RunResultInfo;
import exception.DivisionByZeroException;
import exception.IncompatibleAction;
import exception.IncompatibleType;
import exception.PathDoesntExistException;
import run.result.EntityResult;
import run.result.PropertyResult;
import run.result.Result;
import world.Grid;
import world.api.AssistFunctions;
import world.creator.WorldCreator;
import world.entity.Coordinate;
import world.entity.Entity;
import world.entity.EntityDefinition;
import world.World;
import world.property.api.*;
import world.property.impl.*;
import world.rule.Rule;
import world.rule.action.*;
import world.rule.action.Set;
import world.rule.action.api.*;
import world.rule.action.calculation.Calculation;
import world.rule.action.calculation.Divide;
import world.rule.action.calculation.Multiply;
import world.rule.action.condition.Condition;
import world.rule.action.condition.MultipleCondition;
import world.rule.action.Proximity;
import world.rule.action.condition.SingleCondition;
import xml.reader.XMLReader;
import xml.reader.schema.generated.PRDWorld;
import xml.reader.validator.*;

import java.io.*;
import java.lang.reflect.Array;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class Engine implements EngineInterface, Serializable {
    AssistFunctions functions;
    World world;
    int ticks;
    ArrayList<Result> runResults;
    public Engine(){
        functions = new AssistFunctions();
        runResults = new ArrayList<>();
        ticks = 0;
    }

    public void setWorld(World world) {
        this.world = world;
    }
    @Override
    public Boolean createSimulationByXMLFile(String fileName) throws FileDoesntExistException, InvalidXMLFileNameException, EnvironmentException, EntityException, PropertyException, MustBeNumberException, RuleException, TerminationException {
        XMLReader reader = new XMLReader();
        PRDWorld prdWorld = reader.validateXMLFileAndCreatePRDWorld(fileName);

        // if file opened successfully, but the data is incorrect, then prdWorld would be null
        if(prdWorld != null){
            WorldCreator worldCreator = new WorldCreator();
            /*world = worldCreator.createWorldFromXMLFile(prdWorld);*/ // todo: להחזיר את זה !
            hardCodedMaster2(); // todo delete later
            functions.setEnvironmentVariables(world.getEnvironmentVariables());
            return true;
        } else {
            // something wrong with the data
            return false;
        }
    }
    @Override
    public void cleanResults() {
        runResults.clear();
    }
    @Override
    public ArrayList<PropertyInfo> getEnvironmentDefinitions(){
        ArrayList<PropertyInfo> res = new ArrayList<>();
        for(PropertyDefinition propertyDef : world.getEnvironmentDefinition()){
            PropertyInfo item = new PropertyInfo(propertyDef.getName(), propertyDef.getType(),
                    propertyDef.getTopLimit(), propertyDef.getBottomLimit(), false);
            res.add(item);
        }
        return res;
    }
    @Override
    public ArrayList<PropertyValueInfo> getEnvironmentValues() {
        return world.getEnvironmentValues();
    }
    @Override
    public void setEnvironmentVariable(String name, Object val)throws IncompatibleType {
        world.setEnvironmentValue(name, val);
    }

    @Override // new version -> gon
    public EndSimulationData runSimulation2(DataFromUser detailsToRun) throws DivisionByZeroException, IncompatibleAction, IncompatibleType {
        updateDataFromUser(detailsToRun);
        ticks=1;
        LocalDateTime currentDateTime = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy | HH.mm.ss");
        String formattedDateTime = currentDateTime.format(formatter);

        world.generateEntitiesByDefinitions(); // todo : delete
        world.generateAllEntitiesMapByDefinition(); // new
        world.generateDefinitionForSecondaryEntity(); // new
        world.generateRandomPositionsOnGrid(); // scatter the entities on the grid randomly
        long startTime = System.currentTimeMillis();
        while (simulationShouldRun(startTime)) {
            functions.setNumOfTicksInSimulation(ticks); // update in functions on which tick we are
            world.moveAllEntitiesOnGrid(); // 1. move all entities on grid
            runRulesOnEachEntity(makeListOfAllActionsThatShouldRun()); // 2. make a list of all the actions that should run
            ticks++;
        }

        saveRunResults(formattedDateTime);
        world.cleanup();
        return getEndSimulationData();
    }

    public void runRulesOnEachEntity(List<Action> actionsThatShouldRun) throws IncompatibleAction, DivisionByZeroException, IncompatibleType {
        ArrayList<Entity> entitiesToKill = new ArrayList<>();
        ArrayList<Entity> entitiesToCreate = new ArrayList<>();

        for (Map.Entry<String, ArrayList<Entity>> entry : world.getAllEntities().entrySet()) {
            ArrayList<Entity> entityList = entry.getValue();
            for(Entity entity : entityList) {
                for(Action action : actionsThatShouldRun) {
                    if (actionShouldRunOnEntity(action, entity)) { // 4. check if the action works on the current instance
                        runAction(action, entity, entitiesToKill, entitiesToCreate);
                    }
                }  // 4.b if not -> skip to the next action
            }
        }
        world.killEntities(entitiesToKill);
        world.createEntities(entitiesToCreate);
    }
    public void runAction(Action action, Entity entity, ArrayList<Entity> entitiesToKill, ArrayList<Entity> entitiesToCreate) throws IncompatibleAction, IncompatibleType, DivisionByZeroException {
        if (actionHasSecondaryEntity(action)) { // 4.a if yes -> check if there is a secondary entity regarding the action
            // 5.a if yes:
            List<Entity> secondaryEntities = makeListOfSecondaryEntities(action); // make a list of all the secondary entities needs to activate
            if(secondaryEntities.isEmpty()){ // if no second entity has answered the condition
                Action newAction = createNewActionWithoutSecondaryEntity(action, entity);
                activateAction(newAction, entity, null, entitiesToKill, entitiesToCreate);
            }
            else {
                for (Entity secondary : secondaryEntities) { // go over all the secondary entities instances and make the action on main entity and secondary entity
                    activateAction(action, entity, secondary, entitiesToKill, entitiesToCreate); // need to send main and secondary entities to activate
                }
            }
        }
        else { // 5.b if not -> do the action on the current entity instance
            activateAction(action, entity, null, entitiesToKill, entitiesToCreate);
        }
    }
    private List<Entity> makeListOfSecondaryEntities(Action action) throws IncompatibleAction, IncompatibleType {
        SecondaryEntity secondaryEntityInfo = action.getSecondEntityInfo();
        Integer count = secondaryEntityInfo.getNumOfSecondEntities();
        List<Entity> secondEntityList = new ArrayList<>();

        // if count=ALL or count>=num of instances -> then select them all
        if(count == null || count >= secondaryEntityInfo.getDefinition().getNumOfInstances()){ // add all
            ArrayList<Entity> entityList = world.getAllEntities().get(secondaryEntityInfo.getName());
            secondEntityList.addAll(entityList);
        } else if(secondaryEntityInfo.getSelection() == null){ // if there is no condition for selection -> then add randomly by the number
            Random random = new Random();
            ArrayList<Entity> entityList = world.getAllEntities().get(secondaryEntityInfo.getName());
            // randomly add entities, until we get to count
            while(secondEntityList.size() < count) {
                int randomIndex = random.nextInt(entityList.size());
                secondEntityList.add(entityList.get(randomIndex));
            }
        } else { // add according to the condition
            ArrayList<Entity> entityList = world.getAllEntities().get(secondaryEntityInfo.getName());
            ArrayList<Entity> shuffledSecondEntities = new ArrayList<>(entityList); // get a copy of secondary list
            Collections.shuffle(shuffledSecondEntities); // shuffle the list to make it random
            Condition condition = action.getSecondEntityInfo().getSelection(); // get the condition for selection:

            int i=0; // if no one been chosen -> the list will be empty
            while(secondEntityList.size() < count && i < shuffledSecondEntities.size()){
                Entity secondEntityToAdd = entityList.get(i++);
                setValueOfExpressionOnAction(condition, secondEntityToAdd);
                ParametersForAction params = fillParamsForSelection(secondEntityToAdd, condition);
                boolean selectionTrue;
                if(condition instanceof SingleCondition){
                    selectionTrue = ((SingleCondition)condition).checkCondition(params);
                } else {
                    selectionTrue = ((MultipleCondition)condition).checkCondition(params);
                }

                if(selectionTrue){
                    secondEntityList.add(secondEntityToAdd);
                }
            }
        }
        return secondEntityList;
    }
    public Action createNewActionWithoutSecondaryEntity(Action action, Entity entity){
        // if the second entity list is empty -> ignore the secondary entity -> create a new list of conditions without it
        Action newAction=null;
        System.out.println("%%U&^%&^$&^");
        if(action instanceof MultipleCondition){ // todo: other action types
            ArrayList<Condition> conditions = ((MultipleCondition) action).getConditions();
            ArrayList<Action> thenActions = ((MultipleCondition) action).getThenActions();
            ArrayList<Action> elseActions = ((MultipleCondition) action).getElseActions();

            ArrayList<Condition> newConditions = new ArrayList<>();
            ArrayList<Action> newThen = new ArrayList<>();
            ArrayList<Action> newElse = new ArrayList<>();

            System.out.println("number of conditions: " + conditions.size());
            int i=1;

            for(Condition c : conditions){ // go over the list of conditions and only perform the ones without the second entity
                System.out.println("condition #" + i++ + " of type: " + c.getActionName());
                System.out.println("main entity in condition: " + c.getMainEntityName());

                if(!c.getMainEntityName().equals(action.getSecondEntityInfo().getName())){
                    newConditions.add(c);
                }
            }

            int j=1; // todo: what if there is no then actions????????????
            for(Action t : thenActions){
                System.out.println("action #" + j++ + " of type: " + t.getActionName());
                System.out.println("main entity in condition: " + t.getMainEntityName());
                if(!(t.getMainEntityName().equals(action.getSecondEntityInfo().getName()))){
                    newThen.add(t);
                }
            }

            int k=1;
            for(Action e : elseActions){
                System.out.println("action #" + k++ + " of type: " + e.getActionName());
                System.out.println("main entity in condition: " + e.getMainEntityName());
                if(!(e.getMainEntityName().equals(action.getSecondEntityInfo().getName()))){
                    newElse.add(e);
                }
            }

            newAction = new MultipleCondition(entity.getName(), null, action.getPropToChangeName(),
                    newThen, newElse, ((MultipleCondition)action).getLogicSign(), newConditions);
        }

        return newAction;
    }
    public void activateAction(Action action, Entity entity, Entity secondary, ArrayList<Entity> entitiesToKill, ArrayList<Entity> entitiesToCreate) throws IncompatibleAction, DivisionByZeroException, IncompatibleType {
        setValueOfExpressionOnAction(action, entity);
        ParametersForAction params = getParametersForAction(action, entity, secondary);
        if (action.activate(params)) { // need to kill
            entitiesToKill.add(entity);
            if(action instanceof Replace){
                // meanwhile without position, so it won't take space on grid !
                entitiesToCreate.add(((Replace)action).getEntityToCreate());
            }
        }
    }
    private void setValueOfExpressionOnAction(Action action, Entity entity){ // todo -> לפצל לפונקציות בצורה יפה יותררר
        if(action instanceof Decrease){
            String expression = action.getExpressionNew().getName();
            Property actionProp = entity.getPropertyByName(action.getPropToChangeName());
            action.getExpressionNew().setValue(getValueOfExpression(expression, entity, actionProp));
        } else if(action instanceof Increase){
            String expression = action.getExpressionNew().getName();
            Property actionProp = entity.getPropertyByName(action.getPropToChangeName());
            action.getExpressionNew().setValue(getValueOfExpression(expression, entity, actionProp));
        } else if(action instanceof Kill){
            return;
        } else if(action instanceof Proximity){ // there is no property
            String expression = action.getExpressionNew().getName();
            action.getExpressionNew().setValue(Integer.parseInt(expression));

            if(((Proximity) action).getThenActions() != null){
                for(Action thenAction : ((Proximity) action).getThenActions()){
                    setValueOfExpressionOnAction(thenAction, entity);
                }
            }
        } else if(action instanceof Replace) {
            // todo
            return;
        } else if(action instanceof Set){
            String expression = action.getExpressionNew().getName();
            Property actionProp = entity.getPropertyByName(action.getPropToChangeName());
            action.getExpressionNew().setValue(getValueOfExpression(expression, entity, actionProp));
        } else if(action instanceof Calculation) {
            Property actionProp = entity.getPropertyByName(action.getPropToChangeName());

            String expression = action.getExpressionNew().getName();
            action.getExpressionNew().setValue(getValueOfExpression(expression, entity, actionProp));

            Object expression2Val = getValueOfExpression(((Calculation) action).getExpression2().getName(), entity, actionProp);
            ((Calculation) action).getExpression2().setValue(expression2Val);
        }

        if(action instanceof Condition){
            if(((Condition) action).getThenActions() != null)
                for (Action thenAction : ((Condition) action).getThenActions())
                    setValueOfExpressionOnAction(thenAction, entity);

            if(((Condition) action).getElseActions() != null)
                for (Action elseAction : ((Condition) action).getElseActions())
                    setValueOfExpressionOnAction(elseAction, entity);

            if(action instanceof SingleCondition){
                String expression = action.getExpressionNew().getName();
                Property actionProp = entity.getPropertyByName(action.getPropToChangeName());
                action.getExpressionNew().setValue(getValueOfExpression(expression, entity, actionProp));
            }
            if(action instanceof MultipleCondition){
                for(Condition condition : ((MultipleCondition)action).getConditions()){
                    setValueOfExpressionOnAction(condition, entity);
                }
            }
        }
    }
    private Object getValueOfExpression(String expression, Entity entity, Property actionProp){
        if(expression == null)
            return null;
        int len = expression.length();
        if(actionProp == null)
            return null;
        if(expression.startsWith("environment"))
            return functions.environment(expression.substring(12, len-1));
        else if (expression.startsWith("random"))
            return functions.random(Integer.parseInt(expression.substring(7, len-1)));
        else if (expression.startsWith("evaluate")){
            functions.setEntities(world.getEntities());
            return functions.evaluate(expression.substring(9, len-1));
        }
        else if (expression.startsWith("percent")){
            functions.setEntities(world.getEntities());
            functions.setNumOfTicksInSimulation(ticks); // todo: maybe don't need?
            return functions.percent(expression.substring(8, len-1));
        }
        else if (expression.startsWith("ticks")){
            functions.setEntities(world.getEntities());
            functions.setNumOfTicksInSimulation(ticks); // todo: maybe don't need?
            return functions.ticks(expression.substring(6, len-1));
        }
        else if (entity.getPropertyByName(expression) != null)
            return entity.getPropertyByName(expression).getVal();
        else
            return getSimpleExpressionValue(expression, actionProp);
    }

    private Object getSimpleExpressionValue(String expression, Property actionProp){
        if (actionProp instanceof IntegerProperty)
            return Integer.parseInt(expression);
        else if (actionProp instanceof FloatProperty)
            return Double.parseDouble(expression);
        else if (actionProp instanceof BooleanProperty)
            return Boolean.parseBoolean(expression);
        else //StringProperty
            return expression;
    }



    ParametersForAction getParametersForAction(Action action, Entity mainEntity, Entity secondaryEntity){
        ParametersForAction params;

        if(action instanceof Proximity){
            ((Proximity) action).setSourcePos(mainEntity.getPosition());

            ArrayList<Action> thenActions = ((Proximity)action).getThenActions();
            ArrayList<ParametersForAction> thenParams = getParametersForCondition(thenActions, mainEntity, secondaryEntity);
            params = new ParametersForCondition(null, mainEntity, secondaryEntity, ticks, thenParams, null);
        } else if(action instanceof Replace){
            // in main entity send an entity to create
            String nameToCreate = ((Replace)action).getEntityToCreateName();
            Entity toCreate = new Entity(nameToCreate, null); // fill properties in Replace
            ((Replace)action).setEntityToCreate(toCreate);
            params = new ParametersForAction(null, null, null, ticks);

            for(EntityDefinition d : world.getEntitiesDefinition()){
                if(d.getName().equals(nameToCreate))
                    System.out.println("@@");
            }

        }
        else if(!(action instanceof Condition)) {
            Property mainProp = mainEntity.getPropertyByName(action.getPropToChangeName());
            params = new ParametersForAction(mainProp, mainEntity, secondaryEntity, ticks);
        }
        else { // add parameters for conditions:
            // add parameters for then actions:
            ArrayList<Action> thenActions = ((Condition)action).getThenActions();
            ArrayList<ParametersForAction> thenParams = getParametersForCondition(thenActions, mainEntity, secondaryEntity);

            // add parameters for else actions:
            ArrayList<Action> elseActions = ((Condition)action).getElseActions();
            ArrayList<ParametersForAction> elseParams = getParametersForCondition(elseActions, mainEntity, secondaryEntity);

            if(action instanceof SingleCondition) {
                Property mainProp = mainEntity.getPropertyByName(action.getPropToChangeName());
                params = new ParametersForCondition(mainProp, mainEntity, secondaryEntity, ticks, thenParams, elseParams);
            }
            else { // multiple condition
                params = getParamsForMulCon((MultipleCondition) action, mainEntity, secondaryEntity, thenParams, elseParams);
            }
        }
        return params;
    }

    ArrayList<ParametersForAction> getParametersForCondition(ArrayList<Action> actionList, Entity mainEntity, Entity secondEntity){
        // todo: maybe change the name to get parameters for list of actions
        ArrayList<ParametersForAction> params = null;

        if(actionList != null){
            params = new ArrayList<>();
            for(Action a : actionList){
                params.add(getParametersForAction(a, mainEntity, secondEntity));
            }
        }
        return params;
    }

    private ParametersForMultipleCondition getParamsForMulCon(MultipleCondition action, Entity mainEntity, Entity secondEntity,
                                                              ArrayList<ParametersForAction> thenParams, ArrayList<ParametersForAction> elseParams){
        ArrayList<ParametersForAction> conditionParams = new ArrayList<>();
        ArrayList<Condition> conditions = action.getConditions();

        for(Condition c : conditions){
            if(c instanceof SingleCondition){
                Property cProp =  mainEntity.getPropertyByName(c.getPropToChangeName());
                conditionParams.add(new ParametersForAction(cProp, mainEntity, secondEntity, ticks));
            } else { // multiple condition
                conditionParams.add(getParamsForMulCon((MultipleCondition) c, mainEntity, secondEntity, null, null));
            }
        }
        return new ParametersForMultipleCondition(null, mainEntity, secondEntity, ticks, thenParams, elseParams, conditionParams);
    }

    private boolean actionHasSecondaryEntity(Action action){
        return action.getSecondEntityInfo() != null;
    }
    private List<Rule> makeListOfAllRulesThatShouldRun(){
        List<Rule> rulesThatShouldRun = new ArrayList<>();
        for (Rule rule : world.getRules()) {
            if (ruleShouldRun(rule))
                rulesThatShouldRun.add(rule);
        }
        return rulesThatShouldRun;
    }
    private List<Action> makeListOfAllActionsThatShouldRun(){
        List<Rule> rulesThatShouldRun = makeListOfAllRulesThatShouldRun();
        List<Action> actionsThatShouldRun = new ArrayList<>();
        for (Rule rule : rulesThatShouldRun) {
            actionsThatShouldRun.addAll(rule.getActions());
        }
        return actionsThatShouldRun;
    }

    ParametersForAction fillParamsForSelection(Entity secondEntity, Condition condition){
        Property mainProp = secondEntity.getPropertyByName(condition.getPropToChangeName());
        return new ParametersForAction(mainProp, secondEntity, null, ticks);
        // the main entity for this selection is actually the secondary entity for the action........ imale!!!
    }

    private void updateDataFromUser(DataFromUser detailsToRun){
        detailsToRun.getPopulation().forEach((entityName, amount)->world.setEntitiesPopulation(entityName, amount));
        detailsToRun.getEnvironment().forEach((varName, value)-> {
            try {
                world.setEnvironmentValue(varName, value);
            } catch (IncompatibleType e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public void saveState(String pathName) throws PathDoesntExistException {
        Path path = Paths.get(pathName);

        if (!(path.isAbsolute() && path.getParent() != null && !path.getFileName().toString().isEmpty()))
            throw new PathDoesntExistException();
        else if(Files.isDirectory(path))
            throw new PathDoesntExistException();

        try (OutputStream fileOut = new FileOutputStream(pathName);
             ObjectOutputStream out = new ObjectOutputStream(fileOut)) {
            out.writeObject(this);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public Engine restoreState(String fileName) throws FileDoesntExistException {
        Path path = Paths.get(fileName);

        if(!path.isAbsolute())
            throw new FileDoesntExistException();
        else if (!(path.isAbsolute() && path.getParent() != null))
            throw new FileDoesntExistException();
        else if(Files.isDirectory(path))
            throw new FileDoesntExistException();

        try (InputStream fileIn = new FileInputStream(fileName);
             ObjectInputStream in = new ObjectInputStream(fileIn)) {
            return (Engine) in.readObject();
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }
        return new Engine();
    }

    private EndSimulationData getEndSimulationData(){
        String endCondition;
        int id, endConditionVal;
        if(world.getEndConditionValueByType("ticks") != null &&
                ticks>world.getEndConditionValueByType("ticks")) {
            endCondition = "ticks";
            endConditionVal = world.getEndConditionValueByType("ticks");
        }
        else {
            endCondition = "seconds";
            endConditionVal = world.getEndConditionValueByType("seconds");
        }

        id = runResults.get(runResults.size()-1).getID();
        return new EndSimulationData(id, endCondition,endConditionVal );
    }

    @Override
    public ArrayList<RunResultInfo> displayRunResultsInformation() {
        ArrayList<RunResultInfo> res = new ArrayList<>();
        for(Result result : runResults){
            ArrayList<EntityResultInfo> entitiesResults = new ArrayList<>();
            result.getEntitiesResults().forEach((entityName, entityResult)->{
                ArrayList<PropertyResultInfo> propsInfo = new ArrayList<>();
                entityResult.getPropertiesResults().forEach((propName, propertyResult)->{
                    PropertyResultInfo propertyInfo = new PropertyResultInfo(propName, propertyResult.getHistogram());
                    propsInfo.add(propertyInfo);
                });
                EntityResultInfo entityInfo = new EntityResultInfo(entityName, propsInfo, entityResult.getNumOfInstanceAtStart(),
                        entityResult.getNumOfInstanceAtEnd());
                entitiesResults.add(entityInfo);
            });
            RunResultInfo resultInfo = new RunResultInfo(entitiesResults, result.getID(), result.getDate());
            res.add(resultInfo);
        }
        return res;
    }

    private void saveRunResults(String date){
        Map<String, EntityResult> entitiesResults = new HashMap<>();
        for(EntityDefinition entityDef : world.getEntitiesDefinition()){
            entitiesResults.put(entityDef.getName(), createEntityResult(entityDef));
        }
        runResults.add(new Result(date, entitiesResults));
    }

    public EntityResult createEntityResult(EntityDefinition entityDef){
        Map<String, PropertyResult> propsResults = new HashMap<>();
        for(PropertyDefinition propDef : entityDef.getPropsDef().values()){
            PropertyResult propResult = new PropertyResult(createPropertyHistogram(propDef.getName(),
                    entityDef.getName()));
            propsResults.put(propDef.getName(), propResult);
        }
        return new EntityResult(propsResults,entityDef.getNumOfInstances(), world.getNumOfEntitiesLeft(entityDef.getName()));
    }

    public Map<Object, Integer> createPropertyHistogram(String propName, String entityName){
        // new version - map of entities
        Map<Object, Integer> res = new HashMap<>();
        ArrayList<Entity> entityList = world.getAllEntities().get(entityName);

        for(Entity entity : entityList){
            if(entity.getName().equals(entityName)){
                Object propVal = entity.getPropertyByName(propName).getVal();
                Integer numOfEntities = res.get(propVal);
                if(numOfEntities == null)
                    numOfEntities =0;
                res.put(propVal,++numOfEntities);
            }
        }
        return res;

        // old version - array list of entities
        /*Map<Object, Integer> res = new HashMap<>();
        for(Entity entity : world.getEntities()){
            if(entity.getName().equals(entityName)){
                Object propVal = entity.getPropertyByName(propName).getVal();
                Integer numOfEntities = res.get(propVal);
                if(numOfEntities == null)
                    numOfEntities =0;
                res.put(propVal,++numOfEntities);
            }
        }
        return res;*/
    }

    private Boolean ruleShouldRun(Rule rule){
        return ticks%rule.getTicks()==0 && Math.random()<=rule.getProbability();
    }
    private Boolean actionShouldRunOnEntity(Action action, Entity entity){
        return action.getMainEntityName().equals(entity.getName());
    }

    private Boolean simulationShouldRun(long startTime){
        Integer numOfSecondsToEnd = world.getEndConditionValueByType("seconds");
        Integer numOfTicksToEnd = world.getEndConditionValueByType("ticks");

        if(numOfSecondsToEnd != null && System.currentTimeMillis() - startTime > numOfSecondsToEnd*1000L)
            return false;

        if(numOfTicksToEnd != null && numOfTicksToEnd<ticks)
            return false;

        return true;
    }
    @Override
    public SimulationInfo displaySimulationDefinitionInformation(){
        return new SimulationInfo(displayEntitiesInformation(), displayRulesInformation(), displayTerminationInfo(), displayEnvironmentVariablesInfo());
    }

    private Map<String, PropertyInfo> displayEnvironmentVariablesInfo(){
        Map<String, PropertyInfo> environmentInfo = new HashMap<>();
        Map<String, Property> environmentVariables = world.getEnvironmentVariables();

        for(String name : environmentVariables.keySet()){
            PropertyDefinition propDef = environmentVariables.get(name).getDefinition();
            Object topLimit = propDef.getTopLimit();
            Object bottomLimit = propDef.getBottomLimit();
            String type;

            if(propDef instanceof IntegerPropertyDefinition){
                type = "Integer";
            } else if (propDef instanceof FloatPropertyDefinition){
                type = "Float";
            } else if (propDef instanceof BooleanPropertyDefinition){
                type = "Boolean";
            } else{ //  if (propDef instanceof StringPropertyDefinition){
                type = "String";
            }

            PropertyInfo propInfo = new PropertyInfo(name, type, topLimit, bottomLimit, false);
            environmentInfo.put(name, propInfo);
        }
        return environmentInfo;
    }
    private ArrayList<EntityInfo> displayEntitiesInformation() {
        ArrayList<EntityInfo> entitiesInfoArray = new ArrayList<>();
        ArrayList<EntityDefinition> entityDef = world.getEntitiesDefinition();

        for (EntityDefinition e : entityDef){
            ArrayList<PropertyInfo> propertiesInfo = displayPropertiesInfo(e);
            EntityInfo entityInfoItem = new EntityInfo(e.getName(), e.getNumOfInstances(), propertiesInfo);
            entitiesInfoArray.add(entityInfoItem);
        }
        return entitiesInfoArray;
    }
    private ArrayList<PropertyInfo> displayPropertiesInfo(EntityDefinition e) {
        ArrayList<PropertyInfo> propertiesInfo = new ArrayList<>();
        e.getPropsDef().values().forEach((propDef)->{
            PropertyInfo propInfoItem = new PropertyInfo(propDef.getName(),propDef.getType(),propDef.getTopLimit(),propDef.getBottomLimit(),propDef.getRandomlyInitialized());
            propertiesInfo.add(propInfoItem);
        });
        return propertiesInfo;
    }
    private ArrayList<RuleInfo> displayRulesInformation(){
        ArrayList<RuleInfo> rulesInfoArray = new ArrayList<>();
        ArrayList<Rule> rulesArray = world.getRules();

        for(Rule r : rulesArray) {
            ArrayList<String> actionNamesArray = displayActionsInformation(r);
            RuleInfo rulesInfoItem = new RuleInfo(r.getName(), r.getTicks(), r.getProbability(), r.getNumOfActions(), actionNamesArray);
            rulesInfoArray.add(rulesInfoItem);
        }
        return rulesInfoArray;
    }
    private ArrayList<String> displayActionsInformation(Rule r){
        ArrayList<String> actionNamesArray = new ArrayList<>();
        ArrayList<Action> actionsArray = r.getActions();
        for(Action a : actionsArray) {
            actionNamesArray.add(a.getActionName());
        }
        return actionNamesArray;
    }
    private ArrayList<TerminationInfo> displayTerminationInfo(){

        ArrayList<TerminationInfo> terminationInfosArray = new ArrayList<>();
        Integer ticksVal = world.getEndConditionValueByType("ticks");
        Integer secondsVal = world.getEndConditionValueByType("seconds");
        TerminationInfo item1 = new TerminationInfo("ticks", ticksVal);
        TerminationInfo item2 = new TerminationInfo("seconds", secondsVal);
        terminationInfosArray.add(item1);
        terminationInfosArray.add(item2);
        return terminationInfosArray;
    }





    /////////// NEED TO DELETE /////////////////////////////////////////////////////////////////////////////////////////////////////////////

    @Override // todo: delete this
    public EndSimulationData runSimulation(DataFromUser detailsToRun) throws DivisionByZeroException, IncompatibleAction, IncompatibleType {
        updateDataFromUser(detailsToRun);
        ticks=1;
        LocalDateTime currentDateTime = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy | HH.mm.ss");
        String formattedDateTime = currentDateTime.format(formatter);

        world.generateEntitiesByDefinitions();
        long startTime = System.currentTimeMillis();

        while (simulationShouldRun(startTime)) {
            for (Rule rule : world.getRules()) {
                if (ruleShouldRun(rule))
                    runRule(rule, ticks);
            }
            ticks++;
        }

        saveRunResults(formattedDateTime);
        world.cleanup();
        return getEndSimulationData();
    }
    private void runRule(Rule rule, int ticks)throws DivisionByZeroException, IncompatibleAction, IncompatibleType { // todo: delete this
        ArrayList<Entity> entitiesToKill = new ArrayList<>();

        for (Entity entity : world.getEntities()) {
            for (Action action : rule.getActions()) {
                if (actionShouldRunOnEntity(action, entity)) {
                    setValueOfExpressionOnAction(action, entity);
                    //if (action.activate(getNeededProperties(action, entity))) { // need to kill
                    if (action.activate(null)) { // need to kill
                        entitiesToKill.add(entity);
                    }
                }
            }
        }
        world.killEntities(entitiesToKill);
    }
    /*    private PropertiesToAction getNeededProperties(Action action, Entity entity) { // todo: delete this
        PropertiesToAction res = null;

        if(!(action instanceof Condition)) {
            Property mainProp = entity.getPropertyByName(action.getPropToChangeName());
            res = new PropertiesToAction(mainProp);
        }
        else {
            ArrayList<Action> thenActions =((Condition)action).getThenActions();
            ArrayList<Action> elseActions = ((Condition)action).getElseActions();

            ArrayList<PropertiesToAction> thenProps = getPropsFromActionsArray(thenActions, entity);
            ArrayList<PropertiesToAction> elseProps = getPropsFromActionsArray(elseActions, entity);


            if(action instanceof SingleCondition) {
                Property mainProp = entity.getPropertyByName(action.getPropToChangeName());
                res = new PropertiesToCondition(mainProp, thenProps, elseProps);
            }

            else if (action instanceof MultipleCondition) {
                res = getPropsFromMultipleConditionAction((MultipleCondition) action, entity,
                        thenProps, elseProps);
            }
        }

        return res;
    }
    private PropertiesToMultipleCondition getPropsFromMultipleConditionAction(MultipleCondition action, // todo: delete this
                                                                              Entity entity, ArrayList<PropertiesToAction> thenProps,
                                                                              ArrayList<PropertiesToAction> elseProps){
        ArrayList<PropertiesToAction> conditionsProps = new ArrayList<>();
        for(Condition condition : action.getConditions()) {
            if (condition instanceof SingleCondition) {
                Property conditionProp =  entity.getPropertyByName(condition.getPropToChangeName());
                conditionsProps.add(new PropertiesToAction(conditionProp));
            }
            else if(condition instanceof MultipleCondition){
                conditionsProps.add(getPropsFromMultipleConditionAction((MultipleCondition) condition,
                        entity, null, null));
            }
        }
        return new PropertiesToMultipleCondition(null, thenProps, elseProps, conditionsProps);
    }
    private ArrayList<PropertiesToAction> getPropsFromActionsArray(ArrayList<Action> actionsArr, Entity entity){ // todo: delete this
        if (actionsArr == null)
            return null;
        ArrayList<PropertiesToAction> res = new ArrayList<>();
        for(Action action : actionsArr){
            res.add(getNeededProperties(action, entity));
        }
        return res;
    }*/

    /////////////////////////////////////// HARD CODED /////////////////////////////////////////////////////////////////////////////////

    private void hardCodedMaster2(){
        Grid grid = new Grid(3, 3);

        Map<String, Property> environmentVariables = hardCodedMaster2Environment();
        ArrayList<EntityDefinition> entitiesDefinition = hardCodedMaster2EntitiesDefinitions();
        ArrayList<Rule> rules = hardCodedMaster2Rules(grid, entitiesDefinition);

        Map<String, Integer> termination = new HashMap<>();
        termination.put("ticks", 5);
        termination.put("seconds", 10);


        setWorld(new World(entitiesDefinition, environmentVariables, rules, termination, grid));
    }

    private Map<String, Property> hardCodedMaster2Environment(){
        Map<String, Property> res = new HashMap<>();
        FloatPropertyDefinition d1 = new FloatPropertyDefinition("e1", false, 15.0, 100.0, 10.0);
        FloatProperty e1 = new FloatProperty(d1);
        BooleanPropertyDefinition d2 = new BooleanPropertyDefinition("e2", false, true);
        BooleanProperty e2 = new BooleanProperty(d2);
        FloatPropertyDefinition d3 = new FloatPropertyDefinition("e3", false, 50.0, 100.2, 10.4);
        FloatProperty e3 = new FloatProperty(d3);
        StringPropertyDefinition d4 = new StringPropertyDefinition("e4", false, "eyya");
        StringProperty e4 = new StringProperty(d4);
        res.put("e1", e1);
        res.put("e2", e2);
        res.put("e3", e3);
        res.put("e4", e4);
        return res;
    }
    private EntityDefinition hardCodedMaster2EntityDefinitionEnt1() {
        FloatPropertyDefinition pd1 = new FloatPropertyDefinition("p1", false, 0.0, 100.0, 0.0);
        FloatPropertyDefinition pd2 = new FloatPropertyDefinition("p2", true, 0.0, 100.0, 0.0);
        BooleanPropertyDefinition pd3 = new BooleanPropertyDefinition("p3", true, null);
        StringPropertyDefinition pd4 = new StringPropertyDefinition("p4", false, "example");
        Map<String, PropertyDefinition> pArr = new HashMap<>();
        pArr.put("p1", pd1);
        pArr.put("p2", pd2);
        pArr.put("p3", pd3);
        pArr.put("p4", pd4);
        return new EntityDefinition("ent-1", 3, pArr);
    }
    private EntityDefinition hardCodedMaster2EntityDefinitionEnt2() {
        FloatPropertyDefinition pd1 = new FloatPropertyDefinition("p1", false, 0.0, 100.0, 0.0);
        FloatPropertyDefinition pd2 = new FloatPropertyDefinition("p2", true, 0.0, 100.0, 0.0);
        Map<String, PropertyDefinition> pArr = new HashMap<>();
        pArr.put("p1", pd1);
        pArr.put("p2", pd2);
        return new EntityDefinition("ent-2", 2, pArr);
    }
    private ArrayList<EntityDefinition> hardCodedMaster2EntitiesDefinitions(){
        ArrayList<EntityDefinition> res = new ArrayList<>();
        res.add(hardCodedMaster2EntityDefinitionEnt1());
        res.add(hardCodedMaster2EntityDefinitionEnt2());
        return res;
    }
    private Rule hardChardCodedMaster2Rule1(){
        Increase r1a1 = new Increase("ent-1", null, "p1", new Expression("3"));
        Decrease r1a2 = new Decrease("ent-1", null, "p2", new Expression("environment(e3)"));
        Multiply r1a3 = new Multiply("ent-1", null, "p1", new Expression("p1"), new Expression("environment(e1)"));
        r1a3.setExpression2(new Expression("environment(e1)"));
        Divide r1a4 = new Divide("ent-1", null, "p2", new Expression("environment(e3)"), new Expression("3.2"));
        r1a4.setExpression2(new Expression("3.2"));
        ArrayList<Action> actionsR1 = new ArrayList<>();
        actionsR1.add(r1a1);
        actionsR1.add(r1a2);
        actionsR1.add(r1a3);
        actionsR1.add(r1a4);
        return new Rule("r1", actionsR1, 0.4, 1);
    }
    private Rule hardChardCodedMaster2Rule2(ArrayList<EntityDefinition> entitiesDefinition){
        // todo: property of condition is expression
        SingleCondition r2a1 = new SingleCondition("ent-1", null, "p1",
                SingleCondition.Operator.BIGGERTHAN, new Expression("4"), null, null);
        SingleCondition r2a2 = new SingleCondition("ent-2", null, "p2",
                SingleCondition.Operator.LESSTHAN, new Expression("3"), null, null);
        /////////////////////////////////////////////////////////////////////////////////////////////////////////
        SingleCondition sc1 = new SingleCondition("ent-1", null, "p4",
                SingleCondition.Operator.NOTEQUAL, new Expression("nothing"), null, null);
        SingleCondition sc2 = new SingleCondition("ent-1", null, "p3",
                SingleCondition.Operator.EQUAL, new Expression("environment(e2)"), null, null);
        ArrayList<Condition> conditions = new ArrayList<>();
        conditions.add(sc1);
        conditions.add(sc2);
        MultipleCondition r2a3 = new MultipleCondition("ent-1", null, null,
                null, null, MultipleCondition.Logic.AND, conditions);
        ////////////////////////////////////////////////////////////////////////////////////////////
        ArrayList<Condition> conditions1 = new ArrayList<>();
        conditions1.add(r2a1);
        conditions1.add(r2a2);
        conditions1.add(r2a3);

        // then actions:
        Increase then1 = new Increase("ent-1", null, "p1", new Expression("3"));
        Set then2 = new Set("ent-1", null, "p1", new Expression("random(3)"));
        ArrayList<Action> thenActions = new ArrayList<>();
        thenActions.add(then1);
        thenActions.add(then2);
        // else actions:
        Kill else1 = new Kill("ent-1", null, null);
        else1.setExpressionNew(null);
        ArrayList<Action> elseActions = new ArrayList<>();
        elseActions.add(else1);
        /////////////////////

        SingleCondition selection = new SingleCondition("ent-2", null, "p1", SingleCondition.Operator.BIGGERTHAN, new Expression("4"), null, null);
        SecondaryEntity sEnt = new SecondaryEntity("ent-2", null, selection);
        MultipleCondition m1 = new MultipleCondition("ent-1", sEnt, null,
                thenActions, elseActions, MultipleCondition.Logic.OR, conditions1);
        m1.setSecondEntityInfo(sEnt);

        ///////////////////////////////////////////////////////////////////
        ArrayList<Action> actionsR2 = new ArrayList<>();
        actionsR2.add(m1);
        return new Rule("r2", actionsR2, 1, 1);
    }
    private Rule hardChardCodedMaster2Rule3(Grid grid, ArrayList<EntityDefinition> entitiesDefinition){
        Increase r3a1 = new Increase("ent-1", null, "p1", new Expression("percent(evaluate(ent-2.p1),environment(e1))"));
        Decrease r3a2 = new Decrease("ent-2", null, "p2", new Expression("evaluate(ent-1.p1)"));

        String nameToCreate="ent-2";
        EntityDefinition defToCreate=null;
        for(EntityDefinition def : entitiesDefinition){
            if(def.getName().equals(nameToCreate)){
                defToCreate = def;
            }
        }
        Replace r3a3 = new Replace("ent-1", null, nameToCreate, "scratch", defToCreate);

        ArrayList<Action> proximityActions = new ArrayList<>();
        proximityActions.add(r3a1);
        proximityActions.add(r3a2);
        proximityActions.add(r3a3);

        Proximity proximity = new Proximity("ent-1", null, null, new Expression("1"), proximityActions, "ent-2", grid);
        ArrayList<Action> actionsR3 = new ArrayList<>();
        actionsR3.add(proximity);
        return new Rule("r3", actionsR3, 1, 1);
    }
    private ArrayList<Rule> hardCodedMaster2Rules(Grid grid, ArrayList<EntityDefinition> entitiesDefinition){
        ArrayList<Rule> rules = new ArrayList<>();
        rules.add(hardChardCodedMaster2Rule1());
        rules.add(hardChardCodedMaster2Rule2(entitiesDefinition));
        //rules.add(hardChardCodedMaster2Rule3(grid, entitiesDefinition));
        rules.add(hardChardCodedMaster2Rule4(entitiesDefinition));
        return rules;
    }

    private Rule hardChardCodedMaster2Rule4(ArrayList<EntityDefinition> entitiesDefinition){
        String nameToCreate="ent-2";
        EntityDefinition defToCreate=null;
        for(EntityDefinition def : entitiesDefinition){
            if(def.getName().equals(nameToCreate)){
                defToCreate = def;
            }
        }

        Replace replace = new Replace("ent-1", null, nameToCreate, "scratch", defToCreate);
        ArrayList<Action> actions = new ArrayList<>();
        actions.add(replace);
        return new Rule("r3", actions, 1, 1);
    }




///////////////////////////////////////////////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////////////////////////////////
//    private Map<String, Property> createEnvironmentSmoker(){
//
//
//        IntegerPropertyDefinition env1d = new IntegerPropertyDefinition("cigarets-critical",
//                true,null, 100, 50 );
//
//        IntegerProperty env1 = new IntegerProperty(env1d);
//
//        IntegerPropertyDefinition env2d = new IntegerPropertyDefinition("cigarets-increase-non-smoker",
//                true, null, 10, 0);
//        IntegerProperty env2 = new IntegerProperty(env2d);
//
//
//        IntegerPropertyDefinition env3d = new IntegerPropertyDefinition("cigarets-increase-already-smoker",
//                true, null, 100, 10);
//        IntegerProperty env3 = new IntegerProperty(env3d);
//
//        Map<String, Property> res = new HashMap<>();
//
//        res.put("cigarets-critical", env1);
//        res.put("cigarets-increase-non-smoker", env2);
//        res.put("cigarets-increase-already-smoker", env3);
//
//        return res;
//
//    }
//    private Map<String, Integer> createEndConditionsSmoker(){
//
//        Map<String, Integer> res = new HashMap<>();
//        res.put("ticks", 200);
//        res.put("seconds", 10);
//        return res;
//    }
//    private ArrayList<Rule> createRulesSmoker(){
//
//        Increase a1 = new Increase("Smoker", "age", "1");
//        ArrayList<Action> actionsR1 = new ArrayList<>();
//        actionsR1.add(a1);
//
//        Rule r1 = new Rule("aging", actionsR1, 1, 12);
//
//
//
//        SingleCondition a2sing1 = new SingleCondition("Smoker", "cigarets-per-month",
//                SingleCondition.Operator.BIGGERTHAN,"environment(cigarets-critical)",
//                null, null);
//
//
//        SingleCondition a2sing2 = new SingleCondition("Smoker", "age",
//                SingleCondition.Operator.BIGGERTHAN, "40", null, null);
//        ArrayList<Condition> a2conditions = new ArrayList<>();
//        a2conditions.add(a2sing1);
//        a2conditions.add(a2sing2);
//
//
//        Increase a2then = new Increase("Smoker", "lung-cancer-progress",
//               "random(5)");
//        ArrayList<Action> a2thenActions = new ArrayList<>();
//        a2thenActions.add(a2then);
//
//        MultipleCondition a2mul = new MultipleCondition("Smoker",null,a2thenActions,
//                null, AND, a2conditions);
//
//
//
//        ArrayList<Action> actionsR2 = new ArrayList<>();
//        actionsR2.add(a2mul);
//        Rule r2 = new Rule("got cancer",actionsR2 , 1, 1);
//
//
//
//
//
//
//        Increase a3Then = new Increase("Smoker", "cigarets-per-month","environment(cigarets-increase-non-smoker)");
//        ArrayList<Action> a3ThenActions = new ArrayList<>();
//        a3ThenActions.add(a3Then);
//
//        Increase a3Else = new Increase("Smoker", "cigarets-per-month","environment(cigarets-increase-already-smoker)");
//        ArrayList<Action> a3ElseActions = new ArrayList<>();
//        a3ElseActions.add(a3Else);
//        SingleCondition a3 = new SingleCondition("Smoker", "cigarets-per-month",
//                SingleCondition.Operator.EQUAL, "0" ,a3ThenActions, a3ElseActions);
//
//
//        ArrayList<Action> r3Actions = new ArrayList<>();
//        r3Actions.add(a3);
//
//
//        Rule r3 = new Rule("more-cigartes",r3Actions , 0.3F, 1);
//
//
//
//
//
//
//
//        Kill r4then = new Kill("Smoker", null);
//        ArrayList<Action> r4ThenActions = new ArrayList<>();
//        r4ThenActions.add(r4then);
//
//        SingleCondition a4 = new SingleCondition("Smoker", "lung-cancer-progress",
//                SingleCondition.Operator.BIGGERTHAN, "90", r4ThenActions, null);
//
//        ArrayList<Action> r4Actions = new ArrayList<>();
//        r4Actions.add(a4);
//
//        Rule r4 = new Rule("death", r4Actions, 1, 1);
//
//        ArrayList<Rule> res = new ArrayList<>();
//        res.add(r1);
//        res.add(r2);
//        res.add(r3);
//        res.add(r4);
//        return res;
//
//    }
//    private ArrayList<EntityDefinition> createEntitiesDefinitionsSmoker() {
//
//
//        IntegerPropertyDefinition p1d = new IntegerPropertyDefinition("lung-cancer-progress",
//                false, 0, 100, 0);
//        IntegerPropertyDefinition p2d = new IntegerPropertyDefinition("age",
//                true, null, 50, 15);
//        IntegerPropertyDefinition p3d = new IntegerPropertyDefinition("cigarets-per-month",
//                true, null, 500, 0);
//
//        Map<String, PropertyDefinition> pArr = new HashMap<>();
//        pArr.put("lung-cancer-progress", p1d);
//        pArr.put("age", p2d);
//        pArr.put("cigarets-per-month", p3d);
//
//        EntityDefinition smokerDefinition = new EntityDefinition("Smoker", 100, pArr);
//        ArrayList<EntityDefinition> res = new ArrayList<>();
//        res.add(smokerDefinition);
//        return res;
//
//
//    }
//    private Map<String, Property> createEnvironmentMaster(){
//
//
//        IntegerPropertyDefinition env1d = new IntegerPropertyDefinition("e1",
//                true,null, 100, 10 );
//
//        IntegerProperty env1 = new IntegerProperty(env1d);
//
//
//        BooleanPropertyDefinition env2d = new BooleanPropertyDefinition("e2", true, null);
//        BooleanProperty env2 = new BooleanProperty(env2d);
//
//
//        FloatPropertyDefinition env3d = new FloatPropertyDefinition("e3",
//                true, null, 100.2, 10.4);
//        FloatProperty env3 = new FloatProperty(env3d);
//
//
//        StringPropertyDefinition env4d = new StringPropertyDefinition("e4", true, null);
//        StringProperty env4 = new StringProperty(env4d);
//
//        Map<String, Property> res = new HashMap<>();
//
//        res.put("e1", env1);
//        res.put("e2", env2);
//        res.put("e3", env3);
//        res.put("e4", env4);
//
//        return res;
//
//    }
//    private Map<String, Integer> createEndConditionsMaster(){
//
//        Map<String, Integer> res = new HashMap<>();
//        res.put("ticks", 480);
//        res.put("seconds", 10);
//        return res;
//    }
//    private ArrayList<Rule> createRulesMaster(){
//
//        Increase r1a1 = new Increase("ent-1", "p1", "3");
//        Decrease r1a2 = new Decrease("ent-1", "p2", "environment(e3)");
//        Multiply r1a3 = new Multiply("ent-1", "p1","5","environment(e1)");
//        //פה צריך לשלוח בארגומנט 1 את ערך P1 ולא את שם הפרופרטי
//        Divide r1a4 = new Divide("ent-1", "p2", "environment(e3)", "3.2");
//
//        ArrayList<Action> actionsR1 = new ArrayList<>();
//        actionsR1.add(r1a1);
//        actionsR1.add(r1a2);
//        actionsR1.add(r1a3);
//        actionsR1.add(r1a4);
//
//        Rule r1 = new Rule("r1", actionsR1, 0.4, 1);
//
//
//
//        SingleCondition r2a1c1 = new SingleCondition("ent-1", "p1",
//                SingleCondition.Operator.BIGGERTHAN,"4", null, null);
//
//
//        SingleCondition r2a1c2 = new SingleCondition("ent-1", "p2",
//                SingleCondition.Operator.LESSTHAN, "3", null, null);
//
//        SingleCondition r2a1c3c1 = new SingleCondition("ent-1", "p4",
//                SingleCondition.Operator.NOTEQUAL, "nothing", null, null);
//
//        SingleCondition r2a1c3c2 = new SingleCondition("ent-1", "p3",
//                SingleCondition.Operator.EQUAL, "environment(e2)", null, null);
//
//        ArrayList<Condition> r2a1c3Conditions = new ArrayList<>();
//        r2a1c3Conditions.add(r2a1c3c1);
//        r2a1c3Conditions.add(r2a1c3c2);
//
//
//        MultipleCondition r2a1c3 = new MultipleCondition(null,null,
//                null,null, AND, r2a1c3Conditions);
//
//        ArrayList<Condition> r2Conditions = new ArrayList<>();
//        r2Conditions.add(r2a1c1);
//        r2Conditions.add(r2a1c2);
//        r2Conditions.add(r2a1c3);
//
//
//        Increase r2a1then1 = new Increase("ent-1", "p1",
//                "3" );
//        Set r2a1then2 = new Set("ent-1", "p1", "random(3)");
//
//        ArrayList<Action> r2ThenActions = new ArrayList<>();
//        r2ThenActions.add(r2a1then1);
//        r2ThenActions.add(r2a1then2);
//
//
//        Kill r2a1else = new Kill("ent-1", null);
//        ArrayList<Action> r2ElseActions = new ArrayList<>();
//        r2ElseActions.add(r2a1else);
//
//        MultipleCondition r2a1 = new MultipleCondition("ent-1",null, r2ThenActions,
//                r2ElseActions,OR,r2Conditions);
//
//
//
//        ArrayList<Action> actionsR2 = new ArrayList<>();
//        actionsR2.add(r2a1);
//        Rule r2 = new Rule("r2",actionsR2 , 1, 1);
//
//
//        ArrayList<Rule> res = new ArrayList<>();
//        res.add(r1);
//        res.add(r2);
//
//        return res;
//
//    }
//    private ArrayList<EntityDefinition> createEntitiesDefinitionsMaster() {
//
//
//        IntegerPropertyDefinition p1d = new IntegerPropertyDefinition("p1",
//                false, 0, 100, 0);
//
//        FloatPropertyDefinition p2d = new FloatPropertyDefinition("p2",
//                true, null, 100.0, 0.0);
//
//        BooleanPropertyDefinition p3d = new BooleanPropertyDefinition("p3",
//                true, null);
//
//        StringPropertyDefinition p4d = new StringPropertyDefinition("p4",
//                false, "example");
//
//        Map<String, PropertyDefinition> pArr = new HashMap<>();
//        pArr.put("p1", p1d);
//        pArr.put("p2", p2d);
//        pArr.put("p3", p3d);
//        pArr.put("p4", p4d);
//
//        EntityDefinition definition = new EntityDefinition("ent-1", 100, pArr);
//        ArrayList<EntityDefinition> res = new ArrayList<>();
//        res.add(definition);
//        return res;
//    }

}


