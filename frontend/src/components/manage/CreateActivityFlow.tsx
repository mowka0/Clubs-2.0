import { FC, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { CreateActivityPicker } from './CreateActivityPicker';
import { ClubPickerModal, type ClubPickerOption } from './ClubPickerModal';
import type { ActivityType } from '../../api/activities';

interface CreateActivityFlowProps {
  /** Whether the flow's first step (type picker) is open. */
  open: boolean;
  /** Clubs the user organizes — the flow only navigates within these. */
  organizerClubs: ClubPickerOption[];
  /** Closes the whole flow (resets internal step state). */
  onClose: () => void;
}

function createRoute(clubId: string, type: ActivityType): string {
  return type === 'event'
    ? `/clubs/${clubId}/events/new`
    : `/clubs/${clubId}/skladchina/new`;
}

/**
 * Controller for the global "тип → клуб → форма" creation flow.
 * Step 1: pick activity type. Step 2: resolve club — auto-select when the user
 * organizes exactly one club, otherwise show a club picker. Then navigate to
 * the per-club create route (CreateEventPage / CreateSkladchinaPage read :id).
 */
export const CreateActivityFlow: FC<CreateActivityFlowProps> = ({
  open,
  organizerClubs,
  onClose,
}) => {
  const navigate = useNavigate();
  const [pendingType, setPendingType] = useState<ActivityType | null>(null);

  const reset = () => {
    setPendingType(null);
    onClose();
  };

  const handlePickType = (type: ActivityType) => {
    if (organizerClubs.length === 1) {
      reset();
      navigate(createRoute(organizerClubs[0]!.id, type));
      return;
    }
    setPendingType(type);
  };

  const handlePickClub = (clubId: string) => {
    if (!pendingType) return;
    const type = pendingType;
    reset();
    navigate(createRoute(clubId, type));
  };

  return (
    <>
      <CreateActivityPicker
        open={open && pendingType === null}
        onClose={reset}
        onPick={handlePickType}
      />
      <ClubPickerModal
        open={pendingType !== null}
        clubs={organizerClubs}
        onClose={reset}
        onPick={handlePickClub}
      />
    </>
  );
};
